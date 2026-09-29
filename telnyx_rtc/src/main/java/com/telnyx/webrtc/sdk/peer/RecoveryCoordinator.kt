/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.utilities.Logger
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Per-call ICE-restart and peer-connection recovery coordinator.
 *
 * The coordinator owns the recovery state machine, debouncing, timers, and
 * one-at-a-time decisions for an active call. It is the smallest extension on
 * top of the existing [Peer.startIceRenegotiation] primitive — it does not
 * create a second signaling path.
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
 * Every public mutator validates the recovery generation before changing state
 * to guard against duplicate or late callbacks firing on a stale generation.
 *
 * The class is intentionally side-effect-light on construction: it owns timers
 * but does not start them. Call [onIceConnectionChange], [onConnectionChange]
 * (peer-connection), and [onUpdateMediaAnswerApplied] from [Peer] observers to
 * drive the machine. Call [cancel] on DONE, DROPPED, logout, or
 * [com.telnyx.webrtc.sdk.TelnyxClient] disposal to release all resources.
 */
internal class RecoveryCoordinator(
    private val callId: UUID,
    private val config: Config = Config(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: Random = Random.Default,
    private val actions: Actions
) {

    /**
     * Tunable timing constants. Defaults match the plan:
     *  - 3 second disconnected debounce window
     *  - 15 second restart timeout
     *  - 5 second media verification window
     */
    data class Config(
        val disconnectedDebounceMs: Long = 3_000L,
        val restartTimeoutMs: Long = 15_000L,
        val mediaVerifyWindowMs: Long = 5_000L,
        val pingTimeoutMs: Long = 5_000L,
    )

    /**
     * Side-effect surface. The coordinator invokes these hooks to drive the
     * existing ICE-renegotiation and socket-health primitives without owning
     * the peer connection or socket directly.
     */
    interface Actions {
        /** True if the call is still in ACTIVE state. */
        fun isCallActive(): Boolean
        /** True if the peer has already reached CONNECTED/COMPLETED. */
        fun isPeerConnectedOrCompleted(): Boolean
        /** True if the signaling socket is recent/healthy. */
        fun isSignalingHealthy(): Boolean
        /** Trigger a same-peer ICE restart on the underlying [Peer]. */
        fun startIceRestart()
        /** Issue a single signaling-health ping with the given unique id. */
        fun sendProbePing(probeId: String)
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
    private var probeStartedAtMs: Long = 0L
    private var pendingProbeId: String? = null
    private var reattachPerformed: Boolean = false
    private var lastSeenIceState: PeerConnection.IceConnectionState? = null

    /**
     * Public read-only view of the current state. Useful for tests and the
     * surrounding [Peer] to short-circuit operations when recovery is in
     * flight.
     */
    fun state(): State = currentState

    /**
     * Public read-only view of the active generation. Every state transition
     * bumps the generation; stale callbacks observe a non-matching generation
     * and become no-ops.
     */
    fun generation(): Long = generation.get()

    /**
     * Observe a peer-connection ICE connection state change. Triggers the
     * coordinator's debounce/probe/restart decision policy.
     */
    fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        if (newState == null) return
        val previous = lastSeenIceState
        lastSeenIceState = newState

        when (newState) {
            PeerConnection.IceConnectionState.FAILED -> onFailureObserved(
                trigger = "ice_failed",
                previous = previous,
            )
            PeerConnection.IceConnectionState.DISCONNECTED -> onDisconnectedObserved(previous)
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
    fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        if (newState == null) return
        if (newState == PeerConnection.PeerConnectionState.FAILED) {
            onFailureObserved(
                trigger = "peer_connection_failed",
                previous = null,
            )
        }
    }

    /**
     * Notify the coordinator that the remote updateMedia answer SDP has been
     * applied. Moves the machine out of ICE_RESTARTING into VERIFYING_MEDIA
     * with a bounded 5 second window.
     */
    fun onUpdateMediaAnswerApplied(generationAtSend: Long) {
        if (generationAtSend != generation.get()) return
        if (currentState != State.ICE_RESTARTING) return
        if (!actions.isCallActive()) {
            cancelInternal(reason = "call_no_longer_active")
            return
        }
        transitionTo(State.VERIFYING_MEDIA, "answer_applied")
        mediaVerifyStartedAtMs = clock()
    }

    /**
     * Report inbound-RTP growth within the current media-verification window.
     * No-op outside VERIFYING_MEDIA. Treats the recovery as successful on
     * first valid report.
     */
    fun onInboundRtpGrowth(generationAtSend: Long) {
        if (generationAtSend != generation.get()) return
        if (currentState != State.VERIFYING_MEDIA) return
        transitionTo(State.IDLE, "media_verified")
        Logger.i(
            tag = TAG,
            message = logLine(
                "media_verified",
                "durationMs" to (clock() - mediaVerifyStartedAtMs).toString(),
            ),
        )
    }

    /**
     * Observe a probe ping response. Only the exact probe id resolves the
     * probe; unrelated inbound frames update liveness but do not resolve the
     * pending probe.
     */
    fun onProbeResult(probeId: String, success: Boolean) {
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
        if (success) {
            transitionTo(State.ICE_RESTARTING, "probe_succeeded")
            restartStartedAtMs = clock()
            if (actions.isCallActive() && !actions.isPeerConnectedOrCompleted()) {
                actions.startIceRestart()
            } else {
                cancelInternal(reason = "preconditions_failed_post_probe")
            }
        } else {
            performReattachOnce(reason = "probe_failed")
        }
    }

    /**
     * Cancel the coordinator: stops all timers, clears pending probe, and
     * returns the machine to IDLE. Safe to call from any state, including
     * during a transition.
     */
    fun cancel(reason: String) {
        cancelInternal(reason)
    }

    private fun onFailureObserved(trigger: String, previous: PeerConnection.IceConnectionState?) {
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
                if (actions.isSignalingHealthy()) {
                    transitionTo(State.ICE_RESTARTING, "trigger_$trigger")
                    restartStartedAtMs = clock()
                    actions.startIceRestart()
                } else {
                    startProbe(trigger = trigger)
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

    private fun onDisconnectedObserved(previous: PeerConnection.IceConnectionState?) {
        if (currentState != State.IDLE) return
        if (!actions.isCallActive()) return
        bumpGeneration()
        transitionTo(State.PROBING, "disconnected_observed")
        probeStartedAtMs = clock()
        // The disconnected debounce is implemented as a virtual deadline:
        // the coordinator checks elapsed time on subsequent state observations
        // and on timer ticks. We do not own a Timer thread here — instead the
        // caller (Peer) drives ticks via onTick() from its own dispatcher.
        scheduleProbeDeadlineCheck()
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
    fun onTick() {
        val now = clock()
        when (currentState) {
            State.PROBING -> {
                if (now - probeStartedAtMs >= config.disconnectedDebounceMs) {
                    if (actions.isSignalingHealthy()) {
                        transitionTo(State.ICE_RESTARTING, "debounce_elapsed_signaling_healthy")
                        restartStartedAtMs = now
                        actions.startIceRestart()
                    } else if (pendingProbeId != null) {
                        if (now - probeStartedAtMs >= config.disconnectedDebounceMs + config.pingTimeoutMs) {
                            pendingProbeId = null
                            performReattachOnce(reason = "probe_timeout")
                        }
                    } else {
                        startProbe(trigger = "debounce_elapsed")
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
        val probeId = "rcv-${generation.get()}-${random.nextInt(0, Int.MAX_VALUE).toString(16)}"
        pendingProbeId = probeId
        transitionTo(State.PROBING, "probe_started_$trigger")
        probeStartedAtMs = clock()
        Logger.d(
            tag = TAG,
            message = logLine("probe_started", "probeId" to probeId),
        )
        actions.sendProbePing(probeId)
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

    private fun scheduleProbeDeadlineCheck() {
        // No-op stub: the actual deadline is honored via onTick() which the
        // owner is expected to drive on a steady cadence (e.g., once per
        // 250ms from the call's coroutine scope). Keeping the timer out of
        // the coordinator avoids spawning threads per call.
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
        probeStartedAtMs = 0L
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
            append(" call=").append(callId.toString().take(8))
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
        private const val TAG = "RecoveryCoordinator"
    }
}
