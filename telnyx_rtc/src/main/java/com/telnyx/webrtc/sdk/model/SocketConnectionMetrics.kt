/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Represents the quality level of a WebSocket connection based on ping interval and jitter metrics.
 * 
 * Quality levels for 30-second server ping intervals:
 * - DISCONNECTED: Not connected to the socket
 * - CALCULATING: Initial connection phase, not enough data to assess quality yet
 * - EXCELLENT: Ping intervals close to 30s with minimal jitter (±2s, <1s jitter)
 * - GOOD: Ping intervals reasonably close to 30s with moderate jitter (±5s, <2s jitter)
 * - FAIR: Ping intervals somewhat variable but functional (±10s, <5s jitter)
 * - POOR: Significant deviation from expected intervals or high jitter
 */
enum class SocketConnectionQuality {
    DISCONNECTED, // Not connected to socket
    CALCULATING,  // Initial connection, insufficient data
    EXCELLENT,    // ~30s ±100ms interval, <100ms jitter
    GOOD,         // ~30s ±200ms interval, <200ms jitter
    FAIR,         // ~30s ±300ms interval, <300ms jitter
    POOR          // Significant deviation from 30s or high jitter
}

/**
 * Contains comprehensive metrics about the WebSocket connection health.
 *
 * @property intervalMs The time between the last two PING messages received (milliseconds)
 * @property averageIntervalMs Rolling average of ping intervals (milliseconds)
 * @property minIntervalMs Minimum observed ping interval (milliseconds)
 * @property maxIntervalMs Maximum observed ping interval (milliseconds)
 * @property jitterMs Variation in ping intervals (standard deviation in milliseconds)
 * @property missedPings Count of expected pings that were not received within the expected interval plus tolerance
 * @property totalPings Total number of pings received
 * @property quality Overall connection quality assessment
 * @property timestamp System time when these metrics were calculated
 * @property lastPingTimestamp System time of the last received ping
 */
data class SocketConnectionMetrics(
    val intervalMs: Long? = null,           // Time between last two pings
    val averageIntervalMs: Long? = null,    // Average interval
    val minIntervalMs: Long? = null,        // Minimum interval observed
    val maxIntervalMs: Long? = null,        // Maximum interval observed  
    val jitterMs: Long? = null,             // Standard deviation of intervals
    val missedPings: Int = 0,               // Missed expected pings
    val totalPings: Int = 0,                // Total pings received
    val quality: SocketConnectionQuality = SocketConnectionQuality.DISCONNECTED,
    val timestamp: Long = System.currentTimeMillis(),
    val lastPingTimestamp: Long? = null
) {
    /**
     * Calculates the percentage of successfully received pings.
     * @return Success rate as a percentage (0-100), or 100 if no pings expected yet
     */
    fun getSuccessRate(): Float {
        val expectedPings = totalPings + missedPings
        return if (expectedPings > 0) {
            (totalPings.toFloat() / expectedPings) * PERCENTAGE_MULTIPLIER
        } else {
            FULL_SUCCESS_RATE
        }
    }

    companion object {
        // Connection quality thresholds (milliseconds)
        private const val EXPECTED_PING_INTERVAL_MS = 30000L // 30 seconds
        private const val EXCELLENT_TOLERANCE_MS = 100L      // ±100ms
        private const val GOOD_TOLERANCE_MS = 200L           // ±200ms
        private const val FAIR_TOLERANCE_MS = 300L           // ±300ms

        // Jitter thresholds (milliseconds)
        private const val LOW_JITTER_THRESHOLD_MS = 100L
        private const val MODERATE_JITTER_THRESHOLD_MS = 200L
        private const val HIGH_JITTER_THRESHOLD_MS = 300L

        // Success rate calculation constants
        private const val PERCENTAGE_MULTIPLIER = 100f
        private const val FULL_SUCCESS_RATE = 100f
        /**
         * Calculates connection quality based on available interval and jitter metrics.
         * Handles cases where we don't have enough data yet during initial connection.
         *
         * @param averageInterval Average time between pings (null if not enough data)
         * @param jitter Variation in ping intervals (null if not enough data)
         * @return Calculated ConnectionQuality based on available data
         */
        fun calculateQuality(
            averageInterval: Long?,
            jitter: Long?
        ): SocketConnectionQuality {
            return when {
                averageInterval == null && jitter == null -> SocketConnectionQuality.CALCULATING
                averageInterval != null && jitter == null -> assessByIntervalOnly(averageInterval)
                averageInterval == null && jitter != null -> assessByJitterOnly(jitter)
                else -> assessByBothMetrics(averageInterval!!, jitter!!)
            }
        }

        /**
         * Assesses connection quality based on interval metrics only.
         */
        private fun assessByIntervalOnly(averageInterval: Long): SocketConnectionQuality {
            val excellentRange = createIntervalRange(EXCELLENT_TOLERANCE_MS)
            val goodRange = createIntervalRange(GOOD_TOLERANCE_MS)
            val fairRange = createIntervalRange(FAIR_TOLERANCE_MS)

            return when {
                averageInterval in excellentRange -> SocketConnectionQuality.EXCELLENT
                averageInterval in goodRange -> SocketConnectionQuality.GOOD
                averageInterval in fairRange -> SocketConnectionQuality.FAIR
                else -> SocketConnectionQuality.POOR
            }
        }

        /**
         * Assesses connection quality based on jitter metrics only.
         */
        private fun assessByJitterOnly(jitter: Long): SocketConnectionQuality {
            return when {
                jitter < LOW_JITTER_THRESHOLD_MS -> SocketConnectionQuality.GOOD
                jitter < MODERATE_JITTER_THRESHOLD_MS -> SocketConnectionQuality.FAIR
                else -> SocketConnectionQuality.POOR
            }
        }

        /**
         * Assesses connection quality using both interval and jitter metrics.
         */
        private fun assessByBothMetrics(averageInterval: Long, jitter: Long): SocketConnectionQuality {
            val excellentRange = createIntervalRange(EXCELLENT_TOLERANCE_MS)
            val goodRange = createIntervalRange(GOOD_TOLERANCE_MS)
            val fairRange = createIntervalRange(FAIR_TOLERANCE_MS)

            return when {
                jitter < LOW_JITTER_THRESHOLD_MS && averageInterval in excellentRange -> SocketConnectionQuality.EXCELLENT
                jitter < MODERATE_JITTER_THRESHOLD_MS && averageInterval in goodRange -> SocketConnectionQuality.GOOD
                jitter < HIGH_JITTER_THRESHOLD_MS && averageInterval in fairRange -> SocketConnectionQuality.FAIR
                else -> SocketConnectionQuality.POOR
            }
        }

        /**
         * Creates an interval range based on the expected ping interval and tolerance.
         */
        private fun createIntervalRange(toleranceMs: Long): LongRange {
            return (EXPECTED_PING_INTERVAL_MS - toleranceMs)..(EXPECTED_PING_INTERVAL_MS + toleranceMs)
        }
    }
}
