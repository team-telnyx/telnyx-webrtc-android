package com.telnyx.webrtc.sdk.stats

/**
 * Represents real-time call quality metrics derived from WebRTC statistics
 */
data class CallQualityMetrics(
    /**
     * Jitter in seconds
     */
    val jitter: Double,

    /**
     * Round-trip time in seconds
     */
    val rtt: Double,

    /**
     * Mean Opinion Score (1.0-5.0)
     */
    val mos: Double,

    /**
     * Call quality rating based on MOS
     */
    val quality: CallQuality,

    /**
     * Inbound audio statistics
     */
    val inboundAudio: Map<String, Any>? = null,

    /**
     * Outbound audio statistics
     */
    val outboundAudio: Map<String, Any>? = null,

    /**
     * Remote inbound audio statistics
     */
    val remoteInboundAudio: Map<String, Any>? = null,

    /**
     * Remote outbound audio statistics
     */
    val remoteOutboundAudio: Map<String, Any>? = null
) {
    /**
     * Creates a map representation of the metrics
     * @return Map containing the metrics
     */
    fun toMap(): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "jitter" to jitter,
            "rtt" to rtt,
            "mos" to mos,
            "quality" to quality.name.lowercase()
        )

        inboundAudio?.let { map["inboundAudio"] = it }
        outboundAudio?.let { map["outboundAudio"] = it }
        remoteInboundAudio?.let { map["remoteInboundAudio"] = it }
        remoteOutboundAudio?.let { map["remoteOutboundAudio"] = it }

        return map
    }
}

/**
 * Quality rating for a WebRTC call based on MOS score
 */
enum class CallQuality {
    /**
     * MOS > 4.2
     */
    EXCELLENT,

    /**
     * 4.1 <= MOS <= 4.2
     */
    GOOD,

    /**
     * 3.7 <= MOS <= 4.0
     */
    FAIR,

    /**
     * 3.1 <= MOS <= 3.6
     */
    POOR,

    /**
     * MOS <= 3.0
     */
    BAD,

    /**
     * Unable to calculate quality
     */
    UNKNOWN
}