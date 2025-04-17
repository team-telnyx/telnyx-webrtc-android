package com.telnyx.webrtc.sdk.stats

import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/**
 * Utility class for calculating Mean Opinion Score (MOS) and call quality metrics
 */
object MOSCalculator {

    /**
     * Calculates the Mean Opinion Score (MOS) based on WebRTC statistics
     * @param jitter Jitter in milliseconds
     * @param rtt Round-trip time in milliseconds
     * @param packetsReceived Number of packets received
     * @param packetsLost Number of packets lost
     * @return MOS score between 1.0 and 5.0
     */
    fun calculateMOS(jitter: Double, rtt: Double, packetsReceived: Int, packetsLost: Int): Double {
        // Simplified R-factor calculation
        val R0 = 93.2 // Base value for G.711 codec
        val Is = 0.0 // Assume no simultaneous transmission impairment
        val Id = calculateDelayImpairment(jitter, rtt) // Delay impairment
        val Ie = calculateEquipmentImpairment(packetsLost, packetsReceived) // Equipment impairment
        val A = 0.0 // Advantage factor (0 for WebRTC)

        val R = R0 - Is - Id - Ie + A

        // Convert R-factor to MOS
        val MOS = 1 + 0.035 * R + 0.000007 * R * (R - 60) * (100 - R)
        return min(max(MOS, 1.0), 5.0) // Clamp MOS between 1 and 5
    }

    /**
     * Determines call quality based on MOS score
     * @param mos Mean Opinion Score
     * @return Call quality rating
     */
    fun getQuality(mos: Double): CallQuality {
        if (mos.isNaN()) {
            return CallQuality.UNKNOWN
        }

        return when {
            mos > 4.2 -> CallQuality.EXCELLENT
            mos in 4.1..4.2 -> CallQuality.GOOD
            mos in 3.7..4.0 -> CallQuality.FAIR
            mos in 3.1..3.6 -> CallQuality.POOR
            else -> CallQuality.BAD
        }
    }

    /**
     * Calculates delay impairment (Id) using RTT and jitter
     * @param jitter Jitter in milliseconds
     * @param rtt Round-trip time in milliseconds
     * @return Delay impairment value
     */
    private fun calculateDelayImpairment(jitter: Double, rtt: Double): Double {
        // Approximate one-way latency as RTT / 2
        val latency = jitter + rtt / 2

        // Simplified formula for delay impairment
        return 0.024 * latency + 0.11 * (latency - 177.3) * if (latency > 177.3) 1.0 else 0.0
    }

    /**
     * Calculates equipment impairment (Ie) based on packet loss
     * @param packetsLost Number of packets lost
     * @param packetsReceived Number of packets received
     * @return Equipment impairment value
     */
    private fun calculateEquipmentImpairment(packetsLost: Int, packetsReceived: Int): Double {
        // Avoid division by zero
        if (packetsReceived + packetsLost == 0) {
            return 0.0
        }

        // Calculate packet loss percentage
        val packetLossPercentage = packetsLost.toDouble() / (packetsReceived + packetsLost) * 100

        // Simplified formula for equipment impairment
        return 20 * log(1 + packetLossPercentage, Math.E)
    }
}