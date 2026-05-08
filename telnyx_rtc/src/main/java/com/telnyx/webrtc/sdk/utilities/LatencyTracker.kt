/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LatencyMetrics
import com.telnyx.webrtc.sdk.model.LatencyMetricsListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Represents a single log entry for CallTimings data in the call report.
 * These entries are added to the 'logs' array in the JSON call report
 * to match the JS SDK's CallTimings log format.
 *
 * @property level Log level (always "info" for CallTimings)
 * @property message The formatted CallTimings message
 * @property timestamp The timestamp when the entry was created
 */
data class CallTimingsLogEntry(
    val level: String,
    val message: String,
    val timestamp: Long
)

/**
 * Tracks and calculates latency metrics for WebRTC call establishment.
 * 
 * This class provides detailed timing information for:
 * - Registration latency: Time from login to CLIENT_READY
 * - Inbound call latency: Time from acceptCall() to first RTP packet
 * - Outbound call latency: Time from newInvite() to first RTP packet
 * 
 * Thread-safe implementation using concurrent data structures.
 */
class LatencyTracker {
    
    companion object {
        private const val TAG = "LatencyTracker"

        // Milestone names for registration
        const val MILESTONE_LOGIN_INITIATED = "login_initiated"
        const val MILESTONE_SOCKET_CONNECTED = "socket_connected"
        const val MILESTONE_LOGIN_SENT = "login_sent"
        const val MILESTONE_CLIENT_READY = "client_ready"

        // Milestone names for calls
        const val MILESTONE_CALL_INITIATED = "call_initiated"
        const val MILESTONE_PEER_CREATED = "peer_connection_created"
        const val MILESTONE_PEER_SETUP_COMPLETE = "peer_setup_complete"
        const val MILESTONE_MEDIA_DEVICES_ACQUIRED = "media_devices_acquired"
        const val MILESTONE_SDP_NEGOTIATION_STARTED = "sdp_negotiation_started"
        const val MILESTONE_LOCAL_SDP_CREATED = "local_sdp_created"
        const val MILESTONE_LOCAL_SDP_SET = "local_sdp_set"
        const val MILESTONE_INVITE_SENT = "invite_sent"
        const val MILESTONE_ANSWER_SENT = "answer_sent"
        const val MILESTONE_REMOTE_SDP_RECEIVED = "remote_sdp_received"
        const val MILESTONE_REMOTE_SDP_SET = "remote_sdp_set"

        // Inbound call specific milestones
        const val MILESTONE_INVITE_RECEIVED = "invite_received"
        const val MILESTONE_ANSWER_INITIATED = "answer_initiated"

        // Outbound call specific milestones
        const val MILESTONE_REMOTE_RINGING = "remote_side_ringing"
        const val MILESTONE_CALL_ANSWERED_REMOTE = "call_answered_by_remote"

        // ICE gathering milestones
        const val MILESTONE_ICE_GATHERING_STARTED = "ice_gathering_started"
        const val MILESTONE_FIRST_ICE_CANDIDATE = "first_ice_candidate"
        const val MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE = "first_srflx_relay_candidate"
        const val MILESTONE_ICE_GATHERING_COMPLETE = "ice_gathering_complete"

        // ICE connection milestones
        const val MILESTONE_ICE_CHECKING = "ice_checking"
        const val MILESTONE_ICE_CONNECTED = "ice_connected"
        const val MILESTONE_ICE_COMPLETED = "ice_completed"

        // DTLS milestones
        const val MILESTONE_DTLS_CONNECTING = "dtls_connecting"
        const val MILESTONE_DTLS_CONNECTED = "dtls_connected"

        // Peer connection milestones
        const val MILESTONE_PEER_CONNECTED = "peer_connected"
        const val MILESTONE_FIRST_RTP_SENT = "first_rtp_sent"
        const val MILESTONE_FIRST_RTP_RECEIVED = "first_rtp_received"
        const val MILESTONE_MEDIA_ACTIVE = "media_active"
        const val MILESTONE_CALL_ACTIVE = "call_active"

        // CallTimings log format column widths (must match JS SDK / portal regex)
        private const val STEP_COLUMN_WIDTH = 40
        private const val DELTA_COLUMN_WIDTH = 10
    }
    
    // Registration tracking
    private var registrationStartTime: Long = 0L
    private val registrationMilestones = ConcurrentHashMap<String, Long>()
    @Volatile
    private var isTrackingRegistration = false
    
    // Per-call tracking
    private data class CallTrackingState(
        val startTime: Long = System.currentTimeMillis(),
        val isOutbound: Boolean = false,
        val iceMode: String = "trickle",
        val milestones: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    )
    private val callTrackers = ConcurrentHashMap<UUID, CallTrackingState>()
    
    // Listeners and flows for metrics delivery
    private var latencyMetricsListener: LatencyMetricsListener? = null
    
    private val _latencyMetricsFlow = MutableSharedFlow<LatencyMetrics>(
        replay = 1,
        extraBufferCapacity = 16
    )
    val latencyMetricsFlow: SharedFlow<LatencyMetrics> = _latencyMetricsFlow.asSharedFlow()
    
    /**
     * Sets the listener for latency metrics callbacks.
     * @param listener The listener to receive metrics updates, or null to remove
     */
    fun setLatencyMetricsListener(listener: LatencyMetricsListener?) {
        this.latencyMetricsListener = listener
    }
    
    // ===== Registration Tracking =====
    
    /**
     * Starts tracking registration latency.
     * Call this when login/connect is initiated.
     */
    fun startRegistrationTracking() {
        registrationStartTime = System.currentTimeMillis()
        registrationMilestones.clear()
        isTrackingRegistration = true
        markRegistrationMilestone(MILESTONE_LOGIN_INITIATED)
    }
    
    /**
     * Records a milestone during registration.
     * @param milestone The name of the milestone
     */
    fun markRegistrationMilestone(milestone: String) {
        if (!isTrackingRegistration) return
        val elapsed = System.currentTimeMillis() - registrationStartTime
        registrationMilestones[milestone] = elapsed
        // Milestones are stored silently; only completion is logged
    }
    
    /**
     * Completes registration tracking and emits metrics.
     * Call this when CLIENT_READY is received.
     */
    fun completeRegistrationTracking() {
        if (!isTrackingRegistration) return

        markRegistrationMilestone(MILESTONE_CLIENT_READY)
        isTrackingRegistration = false

        val registrationLatency = System.currentTimeMillis() - registrationStartTime

        val metrics = LatencyMetrics(
            callId = null,
            isOutbound = false,
            registrationLatencyMs = registrationLatency,
            milestones = registrationMilestones.toMap()
        )

        emitMetrics(metrics)
        Logger.i(TAG, "Registration completed in ${registrationLatency}ms")
        Logger.d(TAG, metrics.toFormattedString())
    }
    
    // ===== Call Tracking =====
    
    /**
     * Starts tracking latency for a new call.
     * @param callId The unique identifier for the call
     * @param isOutbound True for outbound calls, false for inbound
     */
    fun startCallTracking(callId: UUID, isOutbound: Boolean, useTrickleIce: Boolean = true) {
        val state = CallTrackingState(
            startTime = System.currentTimeMillis(),
            isOutbound = isOutbound,
            iceMode = if (useTrickleIce) "trickle" else "standard"
        )
        callTrackers[callId] = state
        markCallMilestone(callId, MILESTONE_CALL_INITIATED)
    }
    
    /**
     * Records a milestone for a specific call.
     * @param callId The call identifier
     * @param milestone The name of the milestone
     */
    fun markCallMilestone(callId: UUID, milestone: String) {
        val state = callTrackers[callId] ?: return
        val elapsed = System.currentTimeMillis() - state.startTime
        state.milestones[milestone] = elapsed
        // Milestones are stored silently; only completion is logged
    }
    
    /**
     * Marks when the first RTP packet is sent or received.
     * @param callId The call identifier
     * @param isSent True if sent, false if received
     */
    fun markFirstRtp(callId: UUID, isSent: Boolean) {
        val milestone = if (isSent) MILESTONE_FIRST_RTP_SENT else MILESTONE_FIRST_RTP_RECEIVED
        markCallMilestone(callId, milestone)
    }
    
    /**
     * Marks when an inbound call invite is received.
     * Call this when the invite arrives, before user answers.
     * @param callId The call identifier
     */
    fun markInviteReceived(callId: UUID) {
        markCallMilestone(callId, MILESTONE_INVITE_RECEIVED)
    }
    
    /**
     * Marks when the user initiates answering an inbound call.
     * Call this at the start of acceptCall().
     * @param callId The call identifier
     */
    fun markAnswerInitiated(callId: UUID) {
        markCallMilestone(callId, MILESTONE_ANSWER_INITIATED)
    }
    
    /**
     * Marks when the remote side starts ringing (outbound calls).
     * Call this when SIP 180 Ringing or equivalent is received.
     * @param callId The call identifier
     */
    fun markRemoteRinging(callId: UUID) {
        markCallMilestone(callId, MILESTONE_REMOTE_RINGING)
    }
    
    /**
     * Marks when the remote side answers the call (outbound calls).
     * Call this when SIP 200 OK or equivalent is received.
     * @param callId The call identifier
     */
    fun markCallAnsweredByRemote(callId: UUID) {
        markCallMilestone(callId, MILESTONE_CALL_ANSWERED_REMOTE)
    }
    
    /**
     * Marks when the first server-reflexive or relay ICE candidate is found.
     * @param callId The call identifier
     * @param candidateType The type of candidate (e.g., "srflx", "relay")
     */
    fun markFirstSrflxRelayCandidate(callId: UUID, candidateType: String) {
        val state = callTrackers[callId] ?: return
        // Only mark if not already marked
        if (!state.milestones.containsKey(MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE)) {
            if (candidateType == "srflx" || candidateType == "relay") {
                markCallMilestone(callId, MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE)
            }
        }
    }
    
    /**
     * Marks when media devices (microphone/camera) are acquired.
     * @param callId The call identifier
     */
    fun markMediaDevicesAcquired(callId: UUID) {
        markCallMilestone(callId, MILESTONE_MEDIA_DEVICES_ACQUIRED)
    }
    
    /**
     * Marks when SDP negotiation starts (createOffer/createAnswer).
     * @param callId The call identifier
     */
    fun markSdpNegotiationStarted(callId: UUID) {
        markCallMilestone(callId, MILESTONE_SDP_NEGOTIATION_STARTED)
    }
    
    /**
     * Marks when peer setup is complete.
     * @param callId The call identifier
     */
    fun markPeerSetupComplete(callId: UUID) {
        markCallMilestone(callId, MILESTONE_PEER_SETUP_COMPLETE)
    }
    
    /**
     * Completes call tracking and emits the final metrics.
     * Call this when the call reaches ACTIVE state.
     * @param callId The call identifier
     */
    fun completeCallTracking(callId: UUID): List<CallTimingsLogEntry> {
        val state = callTrackers[callId] ?: return emptyList()

        markCallMilestone(callId, MILESTONE_CALL_ACTIVE)

        // Generate CallTimings logs BEFORE removing the tracker,
        // so MILESTONE_CALL_ACTIVE is included in the output.
        val timingsLogs = generateCallTimingsLogs(callId)

        val milestones = state.milestones.toMap()
        val totalTime = System.currentTimeMillis() - state.startTime

        // Calculate derived metrics
        val iceGatheringLatency = calculateIceGatheringLatency(milestones)
        val signalingLatency = calculateSignalingLatency(milestones, state.isOutbound)
        val mediaEstablishmentLatency = calculateMediaEstablishmentLatency(milestones)
        val timeToFirstRtp = calculateTimeToFirstRtp(milestones)

        val metrics = LatencyMetrics(
            callId = callId,
            isOutbound = state.isOutbound,
            callSetupLatencyMs = totalTime,
            timeToFirstRtpMs = timeToFirstRtp,
            iceGatheringLatencyMs = iceGatheringLatency,
            signalingLatencyMs = signalingLatency,
            mediaEstablishmentLatencyMs = mediaEstablishmentLatency,
            milestones = milestones
        )

        emitMetrics(metrics)
        Logger.i(TAG, "Call $callId completed in ${totalTime}ms")
        Logger.d(TAG, metrics.toFormattedString())

        // Clean up
        callTrackers.remove(callId)

        return timingsLogs
    }

    /**
     * Ordered milestone-to-step mapping for outbound calls.
     * Maps internal milestone names to JS SDK-compatible step names for log output.
     */
    private val outboundStepMapping = linkedMapOf(
        MILESTONE_CALL_INITIATED to "Call Start",
        MILESTONE_PEER_CREATED to "Peer object created",
        MILESTONE_MEDIA_DEVICES_ACQUIRED to "Media devices acquired",
        MILESTONE_PEER_SETUP_COMPLETE to "Peer setup complete",
        MILESTONE_SDP_NEGOTIATION_STARTED to "SDP negotiation started",
        MILESTONE_LOCAL_SDP_CREATED to "SDP offer generated",
        MILESTONE_LOCAL_SDP_SET to "Local description applied",
        MILESTONE_ICE_GATHERING_STARTED to "ICE candidate gathering started",
        MILESTONE_INVITE_SENT to "SDP sent to server",
        MILESTONE_FIRST_ICE_CANDIDATE to "First ICE candidate found",
        MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE to "First server-reflexive/relay candidate found",
        MILESTONE_REMOTE_RINGING to "Remote side ringing",
        MILESTONE_FIRST_RTP_RECEIVED to "Early media received from server",
        MILESTONE_REMOTE_SDP_RECEIVED to "Remote SDP received",
        MILESTONE_REMOTE_SDP_SET to "Remote description applied",
        MILESTONE_ICE_GATHERING_COMPLETE to "All ICE candidates gathered",
        MILESTONE_ICE_CONNECTED to "ICE connection established",
        MILESTONE_DTLS_CONNECTED to "Secure media channel established (DTLS)",
        MILESTONE_CALL_ANSWERED_REMOTE to "Call answered by remote side",
        MILESTONE_CALL_ACTIVE to "Call is active"
    )

    /**
     * Ordered milestone-to-step mapping for inbound calls.
     */
    private val inboundStepMapping = linkedMapOf(
        MILESTONE_CALL_INITIATED to "Call Start",
        MILESTONE_INVITE_RECEIVED to "Invite received",
        MILESTONE_ANSWER_INITIATED to "Answer initiated",
        MILESTONE_PEER_CREATED to "Peer object created",
        MILESTONE_MEDIA_DEVICES_ACQUIRED to "Media devices acquired",
        MILESTONE_PEER_SETUP_COMPLETE to "Peer setup complete",
        MILESTONE_SDP_NEGOTIATION_STARTED to "SDP negotiation started",
        MILESTONE_LOCAL_SDP_CREATED to "SDP answer generated",
        MILESTONE_LOCAL_SDP_SET to "Local description applied",
        MILESTONE_ICE_GATHERING_STARTED to "ICE candidate gathering started",
        MILESTONE_FIRST_ICE_CANDIDATE to "First ICE candidate found",
        MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE to "First server-reflexive/relay candidate found",
        MILESTONE_ANSWER_SENT to "Answer sent to server",
        MILESTONE_REMOTE_SDP_RECEIVED to "Remote SDP received",
        MILESTONE_REMOTE_SDP_SET to "Remote description applied",
        MILESTONE_ICE_GATHERING_COMPLETE to "All ICE candidates gathered",
        MILESTONE_ICE_CONNECTED to "ICE connection established",
        MILESTONE_DTLS_CONNECTED to "Secure media channel established (DTLS)",
        MILESTONE_CALL_ACTIVE to "Call is active"
    )

    /**
     * Generates CallTimings log entries for a call based on tracked milestones.
     * The output format matches the JS SDK's CallTimings log format:
     *   [CallTimings][direction][mode] <step> <delta_ms> <from_start_ms>
     *
     * @param callId The call identifier
     * @return List of CallTimingsLogEntry objects, or empty list if no tracking data
     */
    fun generateCallTimingsLogs(callId: UUID): List<CallTimingsLogEntry> {
        val state = callTrackers[callId]
        val milestones = state?.milestones?.toMap().orEmpty()
        if (milestones.isEmpty()) return emptyList()

        val direction = if (state.isOutbound) "outbound" else "inbound"
        val mode = state?.iceMode ?: "trickle"
        val prefix = "[CallTimings][$direction][$mode]"

        val stepMapping = if (state.isOutbound) outboundStepMapping else inboundStepMapping
        val entries = mutableListOf<CallTimingsLogEntry>()
        val timestamp = System.currentTimeMillis()

        // Column widths must match the JS SDK format so the call-report-stats
        // portal can parse data rows with its regex:
        //   ~r/^(?<step>.+?)\s{2,}(?<delta>[\d.]+ms|-)\s+(?<from>[\d.]+)ms\s*$/
        // The key requirement is at least 2 spaces between columns.
        val stepColumnWidth = STEP_COLUMN_WIDTH  // left-padded step name column
        val deltaColumnWidth = DELTA_COLUMN_WIDTH  // right-aligned delta column

        // Header entries
        entries.add(
            CallTimingsLogEntry(
                level = "info",
                message = "$prefix Call establishment timing breakdown:",
                timestamp = timestamp
            )
        )
        entries.add(
            CallTimingsLogEntry(
                level = "info",
                message = "$prefix ${String.format(Locale.US, "%-${stepColumnWidth}s", "Step")}${String.format(Locale.US, "%${deltaColumnWidth}s", "Delta")}    From Start",
                timestamp = timestamp
            )
        )
        entries.add(
            CallTimingsLogEntry(
                level = "info",
                message = "$prefix --------------------------------------------------------------------------",
                timestamp = timestamp
            )
        )

        // Step entries — sorted by actual chronological order to avoid negative deltas.
        // The stepMapping defines human-readable labels, but milestones may fire in
        // a different order than the mapping (e.g. Trickle ICE candidates arrive early).
        // We sort recorded milestones by their timestamp so deltas are always positive.
        val recordedSteps = mutableListOf<Pair<String, Long>>()
        for ((milestone, stepName) in stepMapping) {
            val fromStartMs = milestones[milestone] ?: continue
            recordedSteps.add(Pair(stepName, fromStartMs))
        }
        recordedSteps.sortBy { it.second }

        var previousMs: Long? = null
        for ((stepName, fromStartMs) in recordedSteps) {
            val deltaMs = if (previousMs != null) fromStartMs - previousMs else null
            previousMs = fromStartMs

            val stepPadded = String.format(Locale.US, "%-${stepColumnWidth}s", stepName)
            val message = if (deltaMs == null) {
                // First row (Call Start): delta is "-"
                val fromStartStr = String.format(Locale.US, "%.2f", fromStartMs.toDouble())
                "$prefix $stepPadded${String.format(Locale.US, "%${deltaColumnWidth}s", "-")}        ${fromStartStr}ms"
            } else {
                val deltaStr = String.format(Locale.US, "%.2f", deltaMs.toDouble())
                val fromStartStr = String.format(Locale.US, "%.2f", fromStartMs.toDouble())
                "$prefix $stepPadded${String.format(Locale.US, "%${deltaColumnWidth}s", "${deltaStr}ms")}        ${fromStartStr}ms"
            }

            entries.add(
                CallTimingsLogEntry(
                    level = "info",
                    message = message,
                    timestamp = timestamp
                )
            )
        }

        // Footer
        entries.add(
            CallTimingsLogEntry(
                level = "info",
                message = "$prefix --------------------------------------------------------------------------",
                timestamp = timestamp
            )
        )

        return entries
    }

    /**
     * Cancels tracking for a call (e.g., if call fails).
     * @param callId The call identifier
     */
    fun cancelCallTracking(callId: UUID) {
        callTrackers.remove(callId)
    }
    
    /**
     * Gets current metrics snapshot for a call in progress.
     * @param callId The call identifier
     * @return Current metrics or null if not tracking
     */
    fun getCurrentCallMetrics(callId: UUID): LatencyMetrics? {
        val state = callTrackers[callId] ?: return null
        val milestones = state.milestones.toMap()
        val currentTime = System.currentTimeMillis() - state.startTime

        return LatencyMetrics(
            callId = callId,
            isOutbound = state.isOutbound,
            callSetupLatencyMs = currentTime,
            milestones = milestones
        )
    }
    
    // ===== Helper Methods =====
    
    private fun emitMetrics(metrics: LatencyMetrics) {
        latencyMetricsListener?.onLatencyMetrics(metrics)
        _latencyMetricsFlow.tryEmit(metrics)
    }
    
    @Suppress("ReturnCount")
    private fun calculateIceGatheringLatency(milestones: Map<String, Long>): Long? {
        val start = milestones[MILESTONE_FIRST_ICE_CANDIDATE] ?: return null
        val end = milestones[MILESTONE_ICE_GATHERING_COMPLETE] 
            ?: milestones[MILESTONE_ICE_CONNECTED] 
            ?: return null
        return end - start
    }
    
    @Suppress("ReturnCount")
    private fun calculateSignalingLatency(milestones: Map<String, Long>, isOutbound: Boolean): Long? {
        return if (isOutbound) {
            val start = milestones[MILESTONE_INVITE_SENT] ?: return null
            val end = milestones[MILESTONE_REMOTE_SDP_RECEIVED] ?: return null
            end - start
        } else {
            val start = milestones[MILESTONE_CALL_INITIATED] ?: return null
            val end = milestones[MILESTONE_ANSWER_SENT] ?: return null
            end - start
        }
    }
    
    @Suppress("ReturnCount")
    private fun calculateMediaEstablishmentLatency(milestones: Map<String, Long>): Long? {
        val start = milestones[MILESTONE_ICE_CONNECTED] 
            ?: milestones[MILESTONE_PEER_CONNECTED] 
            ?: return null
        val end = milestones[MILESTONE_FIRST_RTP_RECEIVED] 
            ?: milestones[MILESTONE_FIRST_RTP_SENT]
            ?: milestones[MILESTONE_MEDIA_ACTIVE]
            ?: return null
        return if (end > start) end - start else null
    }
    
    private fun calculateTimeToFirstRtp(milestones: Map<String, Long>): Long? {
        return milestones[MILESTONE_FIRST_RTP_RECEIVED] 
            ?: milestones[MILESTONE_FIRST_RTP_SENT]
    }
    
    /**
     * Calculates the answer delay for inbound calls.
     * This is the time from invite received to user answering.
     */
    @Suppress("ReturnCount", "UnusedPrivateMember")
    private fun calculateAnswerDelay(milestones: Map<String, Long>): Long? {
        val inviteReceived = milestones[MILESTONE_INVITE_RECEIVED] ?: return null
        val answerInitiated = milestones[MILESTONE_ANSWER_INITIATED] ?: return null
        return answerInitiated - inviteReceived
    }
    
    /**
     * Calculates the time for remote side to answer (outbound calls).
     * This is from invite sent to call answered.
     */
    @Suppress("ReturnCount", "UnusedPrivateMember")
    private fun calculateRemoteAnswerTime(milestones: Map<String, Long>): Long? {
        val inviteSent = milestones[MILESTONE_INVITE_SENT] ?: return null
        val callAnswered = milestones[MILESTONE_CALL_ANSWERED_REMOTE] ?: return null
        return callAnswered - inviteSent
    }
    
    /**
     * Clears all tracking state.
     */
    fun reset() {
        registrationStartTime = 0L
        registrationMilestones.clear()
        isTrackingRegistration = false
        callTrackers.clear()
    }
}
