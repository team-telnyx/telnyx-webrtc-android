/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.lib.PeerConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Per-call signaling-health and ICE-recovery monitor.
 *
 * Mirrors the iOS `SignalingHealthMonitor` ownership model: this class owns
 * signaling freshness, probe correlation, recovery state, and deadlines;
 * [Peer] owns WebRTC renegotiation; and
 * [com.telnyx.webrtc.sdk.TelnyxClient] owns socket reconnect/reattach.
 *
 * State machine:
 *  - idle:           no failure observed; healthy call.
 *  - probing:        transient failure observed; waiting out the disconnected
 *                    debounce window before deciding whether to escalate.
 *  - iceRestarting:  decided to attempt same-peer ICE restart; tracking the
 *                    restart in flight until the updateMedia answer is applied.
 *  - verifyingMedia: restart offer/answer exchanged; waiting for inbound RTP
 *                    growth before declaring recovery successful.
 *  - reattaching:    signaling unavailable / restart timed out; coordinating
 *                    with [Peer]/[com.telnyx.webrtc.sdk.TelnyxClient] for a
 *                    socket-driven reattach. Performed at most once per
 *                    recovery generation.
 *
 * Each recovery attempt owns a generation token. Asynchronous restart
 * callbacks validate that token before changing state so duplicate or late
 * callbacks from an older attempt become no-ops.
 *
 * The class is intentionally side-effect-light on construction and does not
 * create its own thread. [Peer] serializes calls into the synchronized API and
 * drives [onTick] while a deadline is active. Call [cancel] on terminal call
 * states or disposal to release the active recovery generation.
 */
internal class SignalingHealthMonitor(
    private val callId: UUID,
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val actions: Actions
) {

    /**
     * Tunable timing constants. Defaults match iOS:
     *  - 3 second disconnected debounce window
     *  - 15 second restart timeout
     *  - 5 second media verification window
     *  - signaling is recent for 3 seconds inbound / 45 seconds confirmed outbound
     */
    data class Config(
        val disconnectedDebounceMs: Long = 3_000L,
        val restartTimeoutMs: Long = 15_000L,
        val mediaVerifyWindowMs: Long = 5_000L,
        val pingTimeoutMs: Long = 5_000L,
        val recentInboundActivityMs: Long = 3_000L,
        val confirmedOutboundActivityMs: Long = 45_000L,
    )

    /**
     * Side-effect surface. The monitor invokes these hooks to drive the
     * existing ICE-renegotiation and socket-health primitives without owning
     * the peer connection or socket directly.
     */
    interface Actions {
        /** True if the call is still in ACTIVE state. */
        fun isCallActive(): Boolean
        /** True if the peer has already reached CONNECTED/COMPLETED. */
        fun isPeerConnectedOrCompleted(): Boolean
        /** True when the signaling socket is currently available. */
        fun isSignalingAvailable(): Boolean
        /** Trigger a same-peer ICE restart on the underlying [Peer]. */
        fun startIceRestart(generation: Long)
        /** Issue a single signaling-health ping with the given unique id. */
        fun sendProbePing(probeId: String): Boolean
        /** Notify the call that reattach should be initiated by the client. */
        fun requestReattach()
        /** Optional: dump minimal call/generation context for sanitized logs. */
        fun snapshotForLog(): Map<String, String>
    }

    enum class State { IDLE, PROBING, ICE_RESTARTING, VERIFYING_MEDIA, REATTACHING }

    private val generation = AtomicLong(0L)
    private var currentState: State = State.IDLE
    private var restartStartedAtMs: Long = 0L
    private var mediaVerifyStartedAtMs: Long = 0L
    private var disconnectedStartedAtMs: Long = 0L
    private var probeStartedAtMs: Long = 0L
    private var pendingProbeId: String? = null
    private var reattachPerformed: Boolean = false
    private var lastSeenIceState: PeerConnection.IceConnectionState? = null
    private var lastInboundRtpPacketCount: Long? = null
    private var lastInboundSignalingActivityMs: Long = clock()
    private var lastConfirmedOutboundActivityMs: Long = clock()

    /**
     * Public read-only view of the current state. Useful for tests and the
     * surrounding [Peer] to short-circuit operations when recovery is in
     * flight.
     */
    @Synchronized
    fun state(): State = currentState

    /**
     * Public read-only view of the active recovery generation. Starting or
     * cancelling an attempt bumps the generation so stale callbacks become
     * no-ops.
     */
    fun generation(): Long = generation.get()

    /**
     * Observe a peer-connection ICE connection state change. Triggers the
     * monitor's debounce/probe/restart decision policy.
     */
    @Synchronized
    fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        if (newState == null) return
        val previous = lastSeenIceState
        lastSeenIceState = newState

        when (newState) {
            PeerConnection.IceConnectionState.FAILED -> onFailureObserved(
                trigger = "ice_failed",
            )
            PeerConnection.IceConnectionState.DISCONNECTED -> onDisconnectedObserved()
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> onRecoveredObserved(newState)
            else -> {
                Logger.d(
                    tag = TAG,
                    message = logLine(
                        "ice_state_observed",
                        "state" to newState.name,
                        "previous" to (previous?.name ?: "null"),
                    ),
                )
            }
        }
    }

    /**
     * Observe a peer-connection state change (separate from ICE). Used to
     * detect `FAILED` peer-connection state on top of the ICE connection
     * state.
     */
    @Synchronized
    fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        if (newState == null) return
        when (newState) {
            PeerConnection.PeerConnectionState.FAILED -> onFailureObserved(
                trigger = "peer_connection_failed",
            )
            PeerConnection.PeerConnectionState.DISCONNECTED -> onDisconnectedObserved()
            PeerConnection.PeerConnectionState.CONNECTED -> onRecoveredObserved(
                PeerConnection.IceConnectionState.CONNECTED,
            )
            else -> Unit
        }
    }

    /**
     * Notify the monitor that the remote updateMedia answer SDP has been
     * applied. Moves the machine out of ICE_RESTARTING into VERIFYING_MEDIA
     * with a bounded 5 second window.
     */
    @Synchronized
    fun onUpdateMediaAnswerApplied(generationAtSend: Long) {
        if (generationAtSend != generation.get() || currentState != State.ICE_RESTARTING) return
        if (!actions.isCallActive()) {
            cancelInternal(reason = "call_no_longer_active")
            return
        }
        transitionTo(State.VERIFYING_MEDIA, "answer_applied")
        mediaVerifyStartedAtMs = clock()
        // The SDP answer only proves signaling recovered. Discard any packet
        // count from the old transport and require growth sampled after the
        // answer, matching iOS SignalingHealthMonitor behavior.
        lastInboundRtpPacketCount = null
    }

    /**
     * Notify the monitor that creating, sending, or applying the ICE
     * restart failed. A failure from an old recovery generation is ignored.
     */
    @Synchronized
    fun onIceRestartFailed(generationAtSend: Long, reason: String) {
        if (generationAtSend != generation.get() || currentState != State.ICE_RESTARTING) return
        Logger.e(
            tag = TAG,
            message = logLine("restart_failed", "reason" to reason),
        )
        performReattachOnce(reason = "restart_failed")
    }

    /**
     * Report inbound-RTP growth within the current media-verification window.
     * No-op outside VERIFYING_MEDIA. Treats the recovery as successful on
     * first valid report.
     */
    @Synchronized
    fun onInboundRtpGrowth(generationAtSend: Long) {
        if (generationAtSend != generation.get()) return
        if (currentState != State.VERIFYING_MEDIA) return
        finishMediaVerification()
    }

    /**
     * Receives the inbound audio RTP packet counter from WebRTC stats. The
     * first post-answer sample establishes a baseline; only later growth
     * proves that the restarted transport is carrying media.
     */
    @Synchronized
    fun onInboundRtpSample(packetsReceived: Long) {
        if (currentState != State.VERIFYING_MEDIA) return
        val previous = lastInboundRtpPacketCount
        lastInboundRtpPacketCount = packetsReceived
        if (previous != null && packetsReceived > previous) {
            finishMediaVerification()
        }
    }

    /**
     * Records every inbound signaling frame. Any JSON-RPC response confirms
     * bidirectional signaling activity, but only the exact pending probe id
     * resolves a recovery probe. An error response still proves the signaling
     * path is alive, matching iOS behavior.
     */
    @Synchronized
    fun onSignalingMessage(messageId: String?, hasResult: Boolean, hasError: Boolean) {
        val now = clock()
        lastInboundSignalingActivityMs = now
        if (!hasResult && !hasError) return

        lastConfirmedOutboundActivityMs = now
        if (messageId != null) {
            onProbeResponse(probeId = messageId)
        }
    }

    /**
     * Only the exact probe id resolves the probe; unrelated inbound frames
     * update liveness but do not resolve the pending probe.
     */
    private fun onProbeResponse(probeId: String) {
        if (currentState != State.PROBING) return
        if (pendingProbeId == null || pendingProbeId != probeId) {
            Logger.d(
                tag = TAG,
                message = logLine(
                    "probe_liveness_update",
                    "probeId" to probeId,
                    "matched" to "false",
                ),
            )
            return
        }
        pendingProbeId = null
        transitionTo(State.ICE_RESTARTING, "probe_succeeded")
        restartStartedAtMs = clock()
        if (actions.isCallActive() && !actions.isPeerConnectedOrCompleted()) {
            actions.startIceRestart(generation.get())
        } else {
            cancelInternal(reason = "preconditions_failed_post_probe")
        }
    }

    /**
     * Cancel the monitor: stops all timers, clears pending probe, and
     * returns the machine to IDLE. Safe to call from any state, including
     * during a transition.
     */
    @Synchronized
    fun cancel(reason: String) {
        cancelInternal(reason)
    }

    private fun onFailureObserved(trigger: String) {
        if (!actions.isCallActive()) return
        if (actions.isPeerConnectedOrCompleted()) {
            Logger.d(
                tag = TAG,
                message = logLine(
                    "trigger_ignored_peer_already_connected",
                    "trigger" to trigger,
                ),
            )
            return
        }
        when (currentState) {
            State.IDLE -> {
                bumpGeneration()
                when {
                    !actions.isSignalingAvailable() -> {
                        performReattachOnce(reason = "signaling_unavailable")
                    }
                    hasRecentSignalingActivity() -> {
                        transitionTo(State.ICE_RESTARTING, "trigger_$trigger")
                        restartStartedAtMs = clock()
                        actions.startIceRestart(generation.get())
                    }
                    else -> startProbe(trigger = trigger)
                }
            }
            State.PROBING, State.ICE_RESTARTING, State.VERIFYING_MEDIA, State.REATTACHING -> {
                // Duplicate or late callback: just log and stay in the
                // current generation; the existing timer is still authoritative.
                Logger.d(
                    tag = TAG,
                    message = logLine(
                        "trigger_duplicate",
                        "trigger" to trigger,
                        "state" to currentState.name,
                    ),
                )
            }
        }
    }

    private fun onDisconnectedObserved() {
        if (currentState != State.IDLE) return
        if (!actions.isCallActive()) return
        bumpGeneration()
        transitionTo(State.PROBING, "disconnected_observed")
        disconnectedStartedAtMs = clock()
        probeStartedAtMs = 0L
        // Peer drives the virtual deadline via onTick() from its timer.
    }

    private fun onRecoveredObserved(newState: PeerConnection.IceConnectionState) {
        if (currentState == State.PROBING) {
            // Debounce window cancelled: peer recovered before escalation.
            Logger.i(
                tag = TAG,
                message = logLine(
                    "disconnected_recovered",
                    "state" to newState.name,
                ),
            )
            cancelInternal(reason = "recovered")
        }
    }

    /**
     * Time-tick hook driven by the owning call/dispatcher. Honors the
     * disconnected-debounce, restart-timeout, and media-verification
     * deadlines. Cheap to call; only acts when a deadline has elapsed.
     */
    @Synchronized
    fun onTick() {
        val now = clock()
        when (currentState) {
            State.PROBING -> {
                if (pendingProbeId != null) {
                    if (now - probeStartedAtMs >= config.pingTimeoutMs) {
                        pendingProbeId = null
                        performReattachOnce(reason = "probe_timeout")
                    }
                } else if (disconnectedStartedAtMs == 0L ||
                    now - disconnectedStartedAtMs >= config.disconnectedDebounceMs
                ) {
                    when {
                        !actions.isSignalingAvailable() -> {
                            performReattachOnce(reason = "signaling_unavailable")
                        }
                        hasRecentSignalingActivity() -> {
                            transitionTo(State.ICE_RESTARTING, "debounce_elapsed_signaling_healthy")
                            restartStartedAtMs = now
                            actions.startIceRestart(generation.get())
                        }
                        else -> startProbe(trigger = "debounce_elapsed")
                    }
                }
            }
            State.ICE_RESTARTING -> {
                if (now - restartStartedAtMs >= config.restartTimeoutMs) {
                    Logger.w(
                        tag = TAG,
                        message = logLine("restart_timeout", "elapsedMs" to (now - restartStartedAtMs).toString()),
                    )
                    performReattachOnce(reason = "restart_timeout")
                }
            }
            State.VERIFYING_MEDIA -> {
                if (now - mediaVerifyStartedAtMs >= config.mediaVerifyWindowMs) {
                    Logger.w(
                        tag = TAG,
                        message = logLine("media_verify_timeout", "elapsedMs" to (now - mediaVerifyStartedAtMs).toString()),
                    )
                    performReattachOnce(reason = "media_verify_timeout")
                }
            }
            else -> Unit
        }
    }

    private fun startProbe(trigger: String) {
        val probeId = "$PROBE_ID_PREFIX-${generation.get()}-${
            random.nextInt(0, PROBE_ID_RANDOM_BOUND).toString(PROBE_ID_RANDOM_RADIX)
        }"
        pendingProbeId = probeId
        transitionTo(State.PROBING, "probe_started_$trigger")
        probeStartedAtMs = clock()
        Logger.d(
            tag = TAG,
            message = logLine("probe_started", "probeId" to probeId),
        )
        if (!actions.sendProbePing(probeId)) {
            pendingProbeId = null
            performReattachOnce(reason = "probe_send_failed")
        }
    }

    private fun performReattachOnce(reason: String) {
        if (reattachPerformed) {
            Logger.w(
                tag = TAG,
                message = logLine("reattach_already_performed", "reason" to reason),
            )
            return
        }
        reattachPerformed = true
        transitionTo(State.REATTACHING, "reattach_$reason")
        actions.requestReattach()
    }

    private fun finishMediaVerification() {
        transitionTo(State.IDLE, "media_verified")
        Logger.i(
            tag = TAG,
            message = logLine(
                "media_verified",
                "durationMs" to (clock() - mediaVerifyStartedAtMs).toString(),
            ),
        )
        mediaVerifyStartedAtMs = 0L
        restartStartedAtMs = 0L
        disconnectedStartedAtMs = 0L
        probeStartedAtMs = 0L
        pendingProbeId = null
        reattachPerformed = false
        lastInboundRtpPacketCount = null
    }

    private fun hasRecentSignalingActivity(): Boolean {
        val now = clock()
        return now - lastInboundSignalingActivityMs <= config.recentInboundActivityMs &&
            now - lastConfirmedOutboundActivityMs <= config.confirmedOutboundActivityMs
    }

    private fun bumpGeneration() {
        generation.incrementAndGet()
    }

    private fun transitionTo(next: State, cause: String) {
        val previous = currentState
        currentState = next
        Logger.i(
            tag = TAG,
            message = logLine(
                "state_transition",
                "from" to previous.name,
                "to" to next.name,
                "cause" to cause,
            ),
        )
    }

    private fun cancelInternal(reason: String) {
        val previous = currentState
        currentState = State.IDLE
        pendingProbeId = null
        reattachPerformed = false
        mediaVerifyStartedAtMs = 0L
        restartStartedAtMs = 0L
        disconnectedStartedAtMs = 0L
        probeStartedAtMs = 0L
        lastInboundRtpPacketCount = null
        bumpGeneration()
        Logger.i(
            tag = TAG,
            message = logLine(
                "recovery_cancelled",
                "from" to previous.name,
                "reason" to reason,
            ),
        )
    }

    private fun logLine(event: String, vararg fields: Pair<String, String>): String {
        val snapshot = try {
            actions.snapshotForLog()
        } catch (_: Throwable) {
            emptyMap()
        }
        val base = buildString {
            append("event=").append(event)
            append(" gen=").append(generation.get())
            append(" call=").append(callId.toString().take(LOG_CALL_ID_PREFIX_LENGTH))
            for ((k, v) in fields) {
                append(' ').append(k).append('=').append(v)
            }
            for ((k, v) in snapshot) {
                append(' ').append(k).append('=').append(v)
            }
        }
        return base
    }

    companion object {
        private const val TAG = "SignalingHealthMonitor"
        private const val PROBE_ID_PREFIX = "rcv"
        private val PROBE_ID_RANDOM_BOUND: Int = Int.MAX_VALUE
        private const val PROBE_ID_RANDOM_RADIX = 16
        private const val LOG_CALL_ID_PREFIX_LENGTH = 8
    }
}
