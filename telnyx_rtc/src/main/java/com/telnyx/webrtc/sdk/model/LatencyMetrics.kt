/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * Data class representing latency measurements for WebRTC call establishment.
 * These metrics help developers understand the time taken for each step in the call flow.
 *
 * All time values are in milliseconds.
 *
 * @property callId The unique identifier for the call these metrics belong to (null for registration metrics)
 * @property isOutbound True if this is an outbound call, false for inbound
 * @property registrationLatencyMs Time from login initiation to successful registration (CLIENT_READY)
 * @property callSetupLatencyMs Time from call initiation (newInvite/acceptCall) to ACTIVE state
 * @property timeToFirstRtpMs Time from call initiation to first RTP packet sent/received
 * @property iceGatheringLatencyMs Time spent gathering ICE candidates
 * @property signalingLatencyMs Time spent in SIP signaling (INVITE to answer)
 * @property mediaEstablishmentLatencyMs Time from signaling complete to media flowing
 * @property milestones Detailed breakdown of individual milestones with timestamps
 */
data class LatencyMetrics(
    val callId: UUID? = null,
    val isOutbound: Boolean = false,
    val registrationLatencyMs: Long? = null,
    val callSetupLatencyMs: Long? = null,
    val timeToFirstRtpMs: Long? = null,
    val iceGatheringLatencyMs: Long? = null,
    val signalingLatencyMs: Long? = null,
    val mediaEstablishmentLatencyMs: Long? = null,
    val milestones: Map<String, Long> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Returns a formatted string representation of the latency metrics.
     */
    fun toFormattedString(): String {
        val direction = if (isOutbound) "OUTBOUND" else "INBOUND"
        val sb = StringBuilder()
        sb.appendLine("═══════════════════════════════════════════════════")
        sb.appendLine("       $direction CALL LATENCY METRICS")
        sb.appendLine("═══════════════════════════════════════════════════")
        
        callId?.let { sb.appendLine("Call ID: $it") }
        registrationLatencyMs?.let { sb.appendLine("Registration Latency:      ${it}ms") }
        callSetupLatencyMs?.let { sb.appendLine("Call Setup Latency:        ${it}ms") }
        timeToFirstRtpMs?.let { sb.appendLine("Time to First RTP:         ${it}ms") }
        iceGatheringLatencyMs?.let { sb.appendLine("ICE Gathering Latency:     ${it}ms") }
        signalingLatencyMs?.let { sb.appendLine("Signaling Latency:         ${it}ms") }
        mediaEstablishmentLatencyMs?.let { sb.appendLine("Media Establishment:       ${it}ms") }
        
        if (milestones.isNotEmpty()) {
            sb.appendLine("───────────────────────────────────────────────────")
            sb.appendLine("Detailed Milestones:")
            milestones.entries.sortedBy { it.value }.forEach { (name, time) ->
                sb.appendLine("  ${name.padEnd(35)} ${time}ms")
            }
        }
        sb.appendLine("═══════════════════════════════════════════════════")
        
        return sb.toString()
    }
}

/**
 * Callback interface for receiving latency metrics updates.
 */
fun interface LatencyMetricsListener {
    /**
     * Called when latency metrics are available.
     * 
     * @param metrics The collected latency metrics
     */
    fun onLatencyMetrics(metrics: LatencyMetrics)
}
