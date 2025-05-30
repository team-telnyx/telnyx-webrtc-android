package com.telnyx.webrtc.common.model

import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.ICECandidate

/**
 * Data class representing a pre-call diagnosis result.
 */
data class PreCallDiagnosis(
    /**
     * Mean Opinion Score (1.0-5.0)
     */
    val mos: Double,

    /**
     * Call quality rating based on MOS
     */
    val quality: CallQuality,

    /**
     * Jitter in seconds
     */
    val jitter: MetricSummary,

    /**
     * Round-trip time in seconds
     */
    val rtt: MetricSummary,

    /**
     * Number of sent bytes
     */
    val bytesSent: Long,

    /**
     * Number of received bytes
     */
    val bytesReceived: Long,

    /**
     * Number of sent packets
     */
    val packetsSent: Long,

    /**
     * Number of received packets
     */
    val packetsReceived: Long,

    /**
     * List of ICE candidates
     */
    val iceCandidates: List<ICECandidate>
)

data class MetricSummary(val min: Double, val max: Double, val avg: Double)
