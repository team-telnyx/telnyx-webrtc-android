package com.telnyx.webrtc.common.util

import android.util.Log
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics

/**
 * Utility class for handling call quality metrics.
 */
object CallQualityUtil {

    private const val TAG = "CallQualityUtil"
    private const val DECIMAL_PLACES = 2
    private const val MS_IN_SECONDS_INT = 1000

    /**
     * Formats call quality metrics for display or logging.
     *
     * @param metrics The call quality metrics to format.
     * @return A formatted string representation of the metrics.
     */
    fun formatMetrics(metrics: CallQualityMetrics): String {
        return """
            Call Quality Metrics:
            - MOS: ${metrics.mos.format(DECIMAL_PLACES)}
            - Quality: ${metrics.quality.toDisplayString()}
            - Jitter: ${(metrics.jitter * MS_IN_SECONDS_INT).format(DECIMAL_PLACES)} ms
            - RTT: ${(metrics.rtt * MS_IN_SECONDS_INT).format(DECIMAL_PLACES)} ms
        """.trimIndent()
    }

    /**
     * Formats a double value to a specified number of decimal places.
     *
     * @param decimals The number of decimal places to format to.
     * @return The formatted string.
     */
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }

    /**
     * Converts a CallQuality enum value to a user-friendly display string.
     *
     * @return A user-friendly string representation of the call quality.
     */
    fun CallQuality.toDisplayString(): String {
        return when (this) {
            CallQuality.EXCELLENT -> "Excellent"
            CallQuality.GOOD -> "Good"
            CallQuality.FAIR -> "Fair"
            CallQuality.POOR -> "Poor"
            CallQuality.BAD -> "Bad"
            CallQuality.UNKNOWN -> "Unknown"
        }
    }

    /**
     * Logs call quality metrics.
     *
     * @param metrics The call quality metrics to log.
     * @param tag Optional custom tag for the log message.
     */
    fun logMetrics(metrics: CallQualityMetrics, tag: String = TAG) {
        Log.d(tag, formatMetrics(metrics))
    }
}