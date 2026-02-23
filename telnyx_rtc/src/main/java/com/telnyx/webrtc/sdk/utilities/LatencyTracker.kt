/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LatencyMetrics
import com.telnyx.webrtc.sdk.model.LatencyMetricsListener
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
        const val MILESTONE_LOCAL_SDP_CREATED = "local_sdp_created"
        const val MILESTONE_LOCAL_SDP_SET = "local_sdp_set"
        const val MILESTONE_INVITE_SENT = "invite_sent"
        const val MILESTONE_ANSWER_SENT = "answer_sent"
        const val MILESTONE_REMOTE_SDP_RECEIVED = "remote_sdp_received"
        const val MILESTONE_REMOTE_SDP_SET = "remote_sdp_set"
        const val MILESTONE_FIRST_ICE_CANDIDATE = "first_ice_candidate"
        const val MILESTONE_ICE_GATHERING_COMPLETE = "ice_gathering_complete"
        const val MILESTONE_ICE_CONNECTED = "ice_connected"
        const val MILESTONE_PEER_CONNECTED = "peer_connected"
        const val MILESTONE_FIRST_RTP_SENT = "first_rtp_sent"
        const val MILESTONE_FIRST_RTP_RECEIVED = "first_rtp_received"
        const val MILESTONE_MEDIA_ACTIVE = "media_active"
        const val MILESTONE_CALL_ACTIVE = "call_active"
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
        Logger.d(TAG, "Registration tracking started")
    }
    
    /**
     * Records a milestone during registration.
     * @param milestone The name of the milestone
     */
    fun markRegistrationMilestone(milestone: String) {
        if (!isTrackingRegistration) return
        val elapsed = System.currentTimeMillis() - registrationStartTime
        registrationMilestones[milestone] = elapsed
        Logger.d(TAG, "Registration milestone: $milestone at ${elapsed}ms")
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
    fun startCallTracking(callId: UUID, isOutbound: Boolean) {
        val state = CallTrackingState(
            startTime = System.currentTimeMillis(),
            isOutbound = isOutbound
        )
        callTrackers[callId] = state
        markCallMilestone(callId, MILESTONE_CALL_INITIATED)
        Logger.d(TAG, "Call tracking started for $callId (${if (isOutbound) "outbound" else "inbound"})")
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
        Logger.d(TAG, "Call $callId milestone: $milestone at ${elapsed}ms")
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
     * Completes call tracking and emits the final metrics.
     * Call this when the call reaches ACTIVE state.
     * @param callId The call identifier
     */
    fun completeCallTracking(callId: UUID) {
        val state = callTrackers[callId] ?: return
        
        markCallMilestone(callId, MILESTONE_CALL_ACTIVE)
        
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
    }
    
    /**
     * Cancels tracking for a call (e.g., if call fails).
     * @param callId The call identifier
     */
    fun cancelCallTracking(callId: UUID) {
        callTrackers.remove(callId)
        Logger.d(TAG, "Call tracking cancelled for $callId")
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
     * Clears all tracking state.
     */
    fun reset() {
        registrationStartTime = 0L
        registrationMilestones.clear()
        isTrackingRegistration = false
        callTrackers.clear()
        Logger.d(TAG, "LatencyTracker reset")
    }
}
