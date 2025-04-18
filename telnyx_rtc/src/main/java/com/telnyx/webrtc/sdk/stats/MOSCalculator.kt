package com.telnyx.webrtc.sdk.stats

import kotlin.math.log
import kotlin.math.max
import kotlin.math.min

/**
 * Utility class for calculating Mean Opinion Score (MOS) and call quality metrics
 */
object MOSCalculator {

    private const val BASE_R_FACTOR = 93.2
    private const val SIMULTANEOUS_IMPAIRMENT = 0.0
    private const val ADVANTAGE_FACTOR = 0.0
    private const val R_TO_MOS_COEFF1 = 0.035
    private const val R_TO_MOS_COEFF2 = 0.000007
    private const val R_TO_MOS_CONST1 = 60.0 // Note: Using Double for consistency
    private const val R_TO_MOS_CONST2 = 100.0
    private const val MIN_MOS = 1.0
    private const val MAX_MOS = 5.0

    private const val MOS_THRESHOLD_EXCELLENT = 4.2
    private const val MOS_THRESHOLD_GOOD_LOWER = 4.1 // For range 4.1..4.2
    private const val MOS_THRESHOLD_FAIR_LOWER = 3.7 // For range 3.7..4.0
    private const val MOS_THRESHOLD_FAIR_UPPER = 4.0
    private const val MOS_THRESHOLD_POOR_LOWER = 3.1 // For range 3.1..3.6
    private const val MOS_THRESHOLD_POOR_UPPER = 3.6

    private const val RTT_TO_LATENCY_DIVISOR = 2.0
    private const val DELAY_IMPAIRMENT_COEFF1 = 0.024
    private const val DELAY_IMPAIRMENT_COEFF2 = 0.11
    private const val DELAY_IMPAIRMENT_THRESHOLD = 177.3
    private const val DELAY_THRESHOLD_MULTIPLIER = 1.0
    private const val ZERO_DOUBLE = 0.0

    private const val ZERO_INT = 0
    private const val PERCENTAGE_MULTIPLIER = 100.0
    private const val EQUIPMENT_IMPAIRMENT_COEFF = 20.0

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
        val R0 = BASE_R_FACTOR
        val Is = SIMULTANEOUS_IMPAIRMENT
        val Id = calculateDelayImpairment(jitter, rtt) // Delay impairment
        val Ie = calculateEquipmentImpairment(packetsLost, packetsReceived) // Equipment impairment
        val A = ADVANTAGE_FACTOR

        val R = R0 - Is - Id - Ie + A

        // Convert R-factor to MOS
        val mos = MIN_MOS + R_TO_MOS_COEFF1 * R + R_TO_MOS_COEFF2 * R * (R - R_TO_MOS_CONST1) * (R_TO_MOS_CONST2 - R)
        return min(max(mos, MIN_MOS), MAX_MOS) // Clamp MOS between 1 and 5
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
            mos > MOS_THRESHOLD_EXCELLENT -> CallQuality.EXCELLENT
            mos in MOS_THRESHOLD_GOOD_LOWER..MOS_THRESHOLD_EXCELLENT -> CallQuality.GOOD // Use upper bound for clarity
            mos in MOS_THRESHOLD_FAIR_LOWER..MOS_THRESHOLD_FAIR_UPPER -> CallQuality.FAIR
            mos in MOS_THRESHOLD_POOR_LOWER..MOS_THRESHOLD_POOR_UPPER -> CallQuality.POOR
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
        val latency = jitter + rtt / RTT_TO_LATENCY_DIVISOR

        // Simplified formula for delay impairment
        return DELAY_IMPAIRMENT_COEFF1 * latency + DELAY_IMPAIRMENT_COEFF2 * (latency - DELAY_IMPAIRMENT_THRESHOLD) *
            if (latency > DELAY_IMPAIRMENT_THRESHOLD) DELAY_THRESHOLD_MULTIPLIER else ZERO_DOUBLE
    }

    /**
     * Calculates equipment impairment (Ie) based on packet loss
     * @param packetsLost Number of packets lost
     * @param packetsReceived Number of packets received
     * @return Equipment impairment value
     */
    private fun calculateEquipmentImpairment(packetsLost: Int, packetsReceived: Int): Double {
        val totalPackets = packetsReceived + packetsLost
        // Avoid division by zero
        if (totalPackets == ZERO_INT) {
            return ZERO_DOUBLE
        }

        // Calculate packet loss percentage
        val packetLossPercentage = packetsLost.toDouble() / totalPackets * PERCENTAGE_MULTIPLIER

        // Simplified formula for equipment impairment
        // Using Math.E explicitly for clarity as required by log function signature
        return EQUIPMENT_IMPAIRMENT_COEFF * log(1 + packetLossPercentage, Math.E)
    }
}