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
            (totalPings.toFloat() / expectedPings) * 100f
        } else {
            100f
        }
    }
    
    companion object Companion {
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
                // Both null - not enough data yet
                averageInterval == null && jitter == null -> SocketConnectionQuality.CALCULATING
                
                // Have interval but no jitter (first few pings) - assess based on interval only
                averageInterval != null && jitter == null -> {
                    when {
                        averageInterval in 29900..30100 -> SocketConnectionQuality.EXCELLENT  // ±100ms from 30s
                        averageInterval in 29800..30200 -> SocketConnectionQuality.GOOD       // ±200ms from 30s
                        averageInterval in 29700..30300 -> SocketConnectionQuality.FAIR       // ±300ms from 30s
                        else -> SocketConnectionQuality.POOR
                    }
                }
                
                // Have jitter but no interval (edge case) - assess based on jitter only
                averageInterval == null && jitter != null -> {
                    when {
                        jitter < 100 -> SocketConnectionQuality.GOOD    // Low jitter is generally good
                        jitter < 200 -> SocketConnectionQuality.FAIR    // Moderate jitter
                        else -> SocketConnectionQuality.POOR             // High jitter
                    }
                }
                
                // Have both metrics - full assessment
                else -> {
                    val safeJitter = jitter!!
                    val safeInterval = averageInterval!!
                    when {
                        safeJitter < 100 && safeInterval in 29900..30100 -> SocketConnectionQuality.EXCELLENT  // ~30s ±100ms, low jitter
                        safeJitter < 200 && safeInterval in 29800..30200 -> SocketConnectionQuality.GOOD        // ~30s ±200ms, moderate jitter
                        safeJitter < 300 && safeInterval in 29700..30300 -> SocketConnectionQuality.FAIR        // ~30s ±300ms, high jitter
                        else -> SocketConnectionQuality.POOR
                    }
                }
            }
        }
    }
}