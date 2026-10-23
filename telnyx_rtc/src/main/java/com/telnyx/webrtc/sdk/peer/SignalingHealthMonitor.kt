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
 *  - idle:           no failure observed; periodically checking signaling
 *                    while the call is active.
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
 * drives [onTick] while a call is active or a recovery deadline is active.
 * Call [cancel] on terminal call states or disposal to release the active
 * recovery generation.
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
     *  - active-call signaling health is checked every 3 seconds
     *  - inbound signaling is considered stale after 20 seconds
     */
    data class Config(
        val disconnectedDebounceMs: Long = 3_000L,
        val restartTimeoutMs: Long = 15_000L,
        val mediaVerifyWindowMs: Long = 5_000L,
        val pingTimeoutMs: Long = 5_000L,
        val recentInboundActivityMs: Long = 3_000L,
        val confirmedOutboundActivityMs: Long = 45_000L,
        val staleInboundActivityMs: Long = 20_000L,
        val signalingHealthCheckIntervalMs: Long = 3_000L,
        /**
         * Upper bound on the selected-candidate stats read that decides a
         * relay-only replacement call, so a stalled getStats() cannot
         * prevent the reattach.
         */
        val relayEvaluationTimeoutMs: Long = 1_000L,
    )

    /**
     * Side-effect surface. The monitor invokes these hooks to drive the
     * existing ICE-renegotiation and socket-health primitives without owning
     * the peer connection or socket directly.
     */
    interface Actions {
        /** True if the call is still in ACTIVE state. */
        fun isCallActive(): Boolean
        /** True when the signaling socket is currently available. */
        fun isSignalingAvailable(): Boolean
        /** Trigger a same-peer ICE restart on the underlying [Peer]. */
        fun startIceRestart(generation: Long)
        /** Issue a single signaling-health ping with the given unique id. */
        fun sendProbePing(probeId: String): Boolean
        /**
         * Inspect the selected candidate path before replacing a failed peer.
         * The callback is true only for a direct (non-relay) VPN path.
         */
        fun shouldForceRelayForRecovery(onResult: (Boolean) -> Unit)
        /**
         * Ask the client to initiate reattach. Returns false when the client
         * cannot accept the request (for example, no saved login config).
         */
        fun requestReattach(forceRelay: Boolean): Boolean
        /** Optional: dump minimal call/generation context for sanitized logs. */
        fun snapshotForLog(): Map<String, String>
    }

    enum class State { IDLE, PROBING, ICE_RESTARTING, VERIFYING_MEDIA, REATTACHING }

    private enum class ProbePurpose { MEDIA_RECOVERY, HEALTH_CHECK }

    private val generation = AtomicLong(0L)
    @Volatile
    private var currentState: State = State.IDLE
    private var restartStartedAtMs: Long = 0L
    private var mediaVerifyStartedAtMs: Long = 0L
    private var disconnectedStartedAtMs: Long = 0L
    private var probeStartedAtMs: Long = 0L
    private var pendingProbeId: String? = null
    private var pendingProbePurpose: ProbePurpose? = null
    private var reattachPerformed: Boolean = false
    private var reattachRequestPending: Boolean = false
    private var relayEvaluationStartedAtMs: Long = 0L
    private var shouldEvaluateRelayFallback: Boolean = false
    private var lastSeenIceState: PeerConnection.IceConnectionState? = null
    private var lastInboundRtpPacketCount: Long? = null
    private var lastInboundSignalingActivityMs: Long = clock()
    private var lastConfirmedOutboundActivityMs: Long = clock()
    private var lastSignalingHealthCheckAtMs: Long = clock()

    /**
     * Public read-only view of the current state. Useful for tests and the
     * surrounding [Peer] to short-circuit operations when recovery is in
     * flight.
     */
    fun state(): State = currentState

    /**
     * Public read-only view of the active recovery generation. Starting or
     * cancelling an attempt bumps the generation so stale callbacks become
     * no-ops.
     */
    fun generation(): Long = generation.get()

    /**
     * Starts an ICE restart requested explicitly by the application.
     *
     * The request uses the same generation tracking, timeout, SDP-answer
     * handling, media verification, and reattach fallback as an automatic
     * recovery. It is accepted only for an active call with connected
     * signaling and no recovery already in progress.
     *
     * @return `true` when a restart episode was accepted and started.
     */
    @Synchronized
    fun requestIceRestart(): Boolean {
        if (!actions.isCallActive() ||
            !actions.isSignalingAvailable() ||
            currentState != State.IDLE
        ) {
            return false
        }

        reattachPerformed = false
        reattachRequestPending = false
        relayEvaluationStartedAtMs = 0L
        shouldEvaluateRelayFallback = true
        lastInboundRtpPacketCount = null
        bumpGeneration()
        transitionTo(State.ICE_RESTARTING, "application_requested")
        restartStartedAtMs = clock()
        actions.startIceRestart(generation.get())
        return true
    }

    /**
     * Prepare for the client-owned reconnect that follows a network-path
     * change. This mirrors iOS by preempting any per-peer recovery work and
     * suppressing VPN relay fallback evaluation for an expected path switch.
     */
    @Synchronized
    fun onNetworkPathDidChange() {
        if (!actions.isCallActive() || currentState == State.REATTACHING) return

        pendingProbeId = null
        pendingProbePurpose = null
        reattachPerformed = true
        reattachRequestPending = false
        relayEvaluationStartedAtMs = 0L
        shouldEvaluateRelayFallback = false
        mediaVerifyStartedAtMs = 0L
        restartStartedAtMs = 0L
        disconnectedStartedAtMs = 0L
        probeStartedAtMs = 0L
        lastInboundRtpPacketCount = null
        bumpGeneration()
        transitionTo(State.REATTACHING, "network_path_changed")
    }

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
        val purpose = pendingProbePurpose
        if (pendingProbeId != probeId || purpose == null) {
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
        pendingProbePurpose = null
        probeStartedAtMs = 0L

        when (purpose) {
            ProbePurpose.MEDIA_RECOVERY -> {
                transitionTo(State.ICE_RESTARTING, "media_recovery_probe_succeeded")
                restartStartedAtMs = clock()
                if (actions.isCallActive()) {
                    actions.startIceRestart(generation.get())
                } else {
                    cancelInternal(reason = "preconditions_failed_post_probe")
                }
            }
            ProbePurpose.HEALTH_CHECK -> {
                lastSignalingHealthCheckAtMs = clock()
                reattachPerformed = false
                if (disconnectedStartedAtMs != 0L) {
                    // The peer disconnected while this probe was in flight.
                    // Hand over to the disconnected debounce, still measured
                    // from the original disconnect, instead of dropping it.
                    transitionTo(State.PROBING, "health_check_probe_succeeded_disconnect_pending")
                } else {
                    transitionTo(State.IDLE, "health_check_probe_succeeded")
                }
            }
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
        when (currentState) {
            State.IDLE -> {
                // Match iOS: both ICE FAILED and peer-connection FAILED are
                // definitive recovery triggers. In particular, a DTLS-level
                // peer failure may coexist with connected ICE.
                shouldEvaluateRelayFallback = true
                bumpGeneration()
                beginMediaRecovery(trigger)
            }
            State.PROBING -> {
                when {
                    isDisconnectedDebounceInProgress() -> {
                        // FAILED is definitive. Do not make it wait behind the
                        // transient DISCONNECTED debounce window.
                        shouldEvaluateRelayFallback = true
                        disconnectedStartedAtMs = 0L
                        beginMediaRecovery(trigger)
                    }
                    pendingProbePurpose == ProbePurpose.HEALTH_CHECK -> {
                        // Reuse the exact probe already in flight, but upgrade
                        // its success path so it performs the now-required ICE
                        // restart instead of merely returning to IDLE.
                        shouldEvaluateRelayFallback = true
                        disconnectedStartedAtMs = 0L
                        pendingProbePurpose = ProbePurpose.MEDIA_RECOVERY
                        Logger.i(
                            tag = TAG,
                            message = logLine("health_probe_upgraded", "trigger" to trigger),
                        )
                    }
                    else -> logDuplicateTrigger(trigger)
                }
            }
            State.ICE_RESTARTING, State.VERIFYING_MEDIA, State.REATTACHING -> logDuplicateTrigger(trigger)
        }
    }

    private fun beginMediaRecovery(trigger: String) {
        when {
            !actions.isSignalingAvailable() -> {
                performReattachOnce(reason = "signaling_unavailable")
            }
            hasRecentSignalingActivity() -> {
                transitionTo(State.ICE_RESTARTING, "trigger_$trigger")
                restartStartedAtMs = clock()
                actions.startIceRestart(generation.get())
            }
            else -> startProbe(trigger = trigger, purpose = ProbePurpose.MEDIA_RECOVERY)
        }
    }

    private fun logDuplicateTrigger(trigger: String) {
        Logger.d(
            tag = TAG,
            message = logLine(
                "trigger_duplicate",
                "trigger" to trigger,
                "state" to currentState.name,
            ),
        )
    }

    private fun isDisconnectedDebounceInProgress(): Boolean =
        currentState == State.PROBING && pendingProbeId == null && disconnectedStartedAtMs != 0L

    private fun isHealthCheckProbeInFlight(): Boolean =
        currentState == State.PROBING &&
            pendingProbeId != null &&
            pendingProbePurpose == ProbePurpose.HEALTH_CHECK

    private fun onDisconnectedObserved() {
        if (!actions.isCallActive()) return
        when {
            isHealthCheckProbeInFlight() -> recordDisconnectDuringHealthProbe()
            currentState == State.IDLE -> startDisconnectedDebounce()
            else -> Unit
        }
    }

    /**
     * Keep a disconnect seen alongside an in-flight health probe; the probe
     * outcome decides whether it runs the debounce or reattaches.
     */
    private fun recordDisconnectDuringHealthProbe() {
        if (disconnectedStartedAtMs == 0L) {
            disconnectedStartedAtMs = clock()
        }
        shouldEvaluateRelayFallback = true
        Logger.i(tag = TAG, message = logLine("disconnected_during_health_probe"))
    }

    private fun startDisconnectedDebounce() {
        shouldEvaluateRelayFallback = true
        bumpGeneration()
        transitionTo(State.PROBING, "disconnected_observed")
        disconnectedStartedAtMs = clock()
        probeStartedAtMs = 0L
        // Peer drives the virtual deadline via onTick() from its timer.
    }

    private fun onRecoveredObserved(newState: PeerConnection.IceConnectionState) {
        if (isHealthCheckProbeInFlight() && disconnectedStartedAtMs != 0L) {
            // The peer reconnected before the health probe settled: the probe
            // goes back to being a plain signaling health check.
            disconnectedStartedAtMs = 0L
            shouldEvaluateRelayFallback = false
            Logger.i(
                tag = TAG,
                message = logLine("disconnected_recovered_during_health_probe", "state" to newState.name),
            )
            return
        }
        if (isDisconnectedDebounceInProgress()) {
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
            State.IDLE -> checkActiveCallSignalingHealth(now)
            State.PROBING -> tickProbeOrDisconnectedDebounce(now)
            State.ICE_RESTARTING -> tickIceRestart(now)
            State.VERIFYING_MEDIA -> tickMediaVerification(now)
            State.REATTACHING -> tickRelayEvaluation(now)
        }
    }

    /**
     * True while a reattach is waiting on the relay evaluation stats read;
     * the owner keeps ticking so its deadline can elapse.
     */
    @Synchronized
    fun isAwaitingRelayEvaluation(): Boolean =
        currentState == State.REATTACHING && relayEvaluationStartedAtMs != 0L

    private fun tickRelayEvaluation(now: Long) {
        if (relayEvaluationStartedAtMs == 0L ||
            now - relayEvaluationStartedAtMs < config.relayEvaluationTimeoutMs
        ) {
            return
        }
        Logger.w(
            tag = TAG,
            message = logLine(
                "relay_evaluation_timeout",
                "elapsedMs" to (now - relayEvaluationStartedAtMs).toString(),
            ),
        )
        completeReattachRequest(generation.get(), "relay_evaluation_timeout", forceRelay = false)
    }

    private fun tickProbeOrDisconnectedDebounce(now: Long) {
        if (pendingProbeId != null) {
            if (now - probeStartedAtMs >= config.pingTimeoutMs) {
                pendingProbeId = null
                pendingProbePurpose = null
                performReattachOnce(reason = "probe_timeout")
            }
            return
        }
        if (disconnectedStartedAtMs != 0L &&
            now - disconnectedStartedAtMs < config.disconnectedDebounceMs
        ) {
            return
        }

        when {
            !actions.isSignalingAvailable() -> {
                performReattachOnce(reason = "signaling_unavailable")
            }
            hasRecentSignalingActivity() -> {
                transitionTo(State.ICE_RESTARTING, "debounce_elapsed_signaling_healthy")
                restartStartedAtMs = now
                actions.startIceRestart(generation.get())
            }
            else -> startProbe(
                trigger = "debounce_elapsed",
                purpose = ProbePurpose.MEDIA_RECOVERY,
            )
        }
    }

    private fun tickIceRestart(now: Long) {
        if (now - restartStartedAtMs < config.restartTimeoutMs) return
        Logger.w(
            tag = TAG,
            message = logLine("restart_timeout", "elapsedMs" to (now - restartStartedAtMs).toString()),
        )
        performReattachOnce(reason = "restart_timeout")
    }

    private fun tickMediaVerification(now: Long) {
        if (now - mediaVerifyStartedAtMs < config.mediaVerifyWindowMs) return
        Logger.w(
            tag = TAG,
            message = logLine("media_verify_timeout", "elapsedMs" to (now - mediaVerifyStartedAtMs).toString()),
        )
        performReattachOnce(reason = "media_verify_timeout")
    }

    private fun checkActiveCallSignalingHealth(now: Long) {
        if (!actions.isCallActive()) return
        if (now - lastSignalingHealthCheckAtMs < config.signalingHealthCheckIntervalMs) return
        lastSignalingHealthCheckAtMs = now

        when {
            !actions.isSignalingAvailable() -> {
                shouldEvaluateRelayFallback = false
                bumpGeneration()
                performReattachOnce(reason = "active_call_signaling_unavailable")
            }
            isSignalingStale(now) -> {
                shouldEvaluateRelayFallback = false
                bumpGeneration()
                startProbe(
                    trigger = "active_call_signaling_stale",
                    purpose = ProbePurpose.HEALTH_CHECK,
                )
            }
        }
    }

    private fun isSignalingStale(now: Long): Boolean =
        now - lastInboundSignalingActivityMs >= config.staleInboundActivityMs ||
            now - lastConfirmedOutboundActivityMs >= config.confirmedOutboundActivityMs

    private fun startProbe(trigger: String, purpose: ProbePurpose) {
        val probeId = "$PROBE_ID_PREFIX-${generation.get()}-${
            random.nextInt(0, PROBE_ID_RANDOM_BOUND).toString(PROBE_ID_RANDOM_RADIX)
        }"
        pendingProbeId = probeId
        pendingProbePurpose = purpose
        transitionTo(State.PROBING, "probe_started_$trigger")
        probeStartedAtMs = clock()
        Logger.d(
            tag = TAG,
            message = logLine("probe_started", "probeId" to probeId),
        )
        if (!actions.sendProbePing(probeId)) {
            pendingProbeId = null
            pendingProbePurpose = null
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
        reattachRequestPending = true
        val generationAtRequest = generation.get()
        val evaluateRelayFallback = shouldEvaluateRelayFallback
        shouldEvaluateRelayFallback = false
        transitionTo(State.REATTACHING, "reattach_$reason")

        if (evaluateRelayFallback) {
            relayEvaluationStartedAtMs = clock()
            actions.shouldForceRelayForRecovery { forceRelay ->
                completeReattachRequest(generationAtRequest, reason, forceRelay)
            }
        } else {
            completeReattachRequest(generationAtRequest, reason, forceRelay = false)
        }
    }

    @Synchronized
    private fun completeReattachRequest(
        generationAtRequest: Long,
        reason: String,
        forceRelay: Boolean,
    ) {
        // reattachRequestPending makes this one-shot: a relay evaluation that
        // answers after its timeout cannot request a second reattach.
        if (generationAtRequest != generation.get() ||
            currentState != State.REATTACHING ||
            !reattachRequestPending
        ) {
            return
        }
        reattachRequestPending = false
        relayEvaluationStartedAtMs = 0L

        if (!actions.requestReattach(forceRelay)) {
            Logger.e(
                tag = TAG,
                message = logLine("reattach_rejected", "reason" to reason),
            )
            cancelInternal(reason = "reattach_rejected")
        }
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
        pendingProbePurpose = null
        reattachPerformed = false
        shouldEvaluateRelayFallback = false
        lastInboundRtpPacketCount = null
        lastSignalingHealthCheckAtMs = clock()
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
        pendingProbePurpose = null
        reattachPerformed = false
        reattachRequestPending = false
        relayEvaluationStartedAtMs = 0L
        shouldEvaluateRelayFallback = false
        mediaVerifyStartedAtMs = 0L
        restartStartedAtMs = 0L
        disconnectedStartedAtMs = 0L
        probeStartedAtMs = 0L
        lastInboundRtpPacketCount = null
        lastSignalingHealthCheckAtMs = clock()
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
