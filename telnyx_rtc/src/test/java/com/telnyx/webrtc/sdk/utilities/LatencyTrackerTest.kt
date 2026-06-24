package com.telnyx.webrtc.sdk.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.regex.Pattern

class LatencyTrackerTest {

    private lateinit var tracker: LatencyTracker

    @Before
    fun setUp() {
        tracker = LatencyTracker()
    }

    // ===== ICE Mode Tests =====

    @Test
    fun `outbound call with trickle ICE reports trickle mode`() {
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = true, useTrickleIce = true)

        // Simulate some milestones to get a complete call
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)
        assertTrue("Should produce log entries", logs.isNotEmpty())
        assertTrue(
            "Should report trickle mode",
            logs.any { it.message.contains("[trickle]") }
        )
    }

    @Test
    fun `outbound call with standard ICE reports standard mode`() {
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = true, useTrickleIce = false)

        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)
        assertTrue(
            "Should report standard mode",
            logs.any { it.message.contains("[standard]") }
        )
    }

    @Test
    fun `inbound call with updated ICE mode reports correct mode after acceptCall`() {
        val callId = UUID.randomUUID()

        // Simulate inbound flow: tracking starts when invite is received
        // At that point, the client field might still be "standard" from a previous call
        tracker.startCallTracking(callId, isOutbound = false, useTrickleIce = false)
        tracker.markInviteReceived(callId)

        // User calls acceptCall with useTrickleIce = true
        // This should update the tracker's ICE mode
        tracker.updateIceMode(callId, useTrickleIce = true)
        tracker.markAnswerInitiated(callId)

        // Complete the call
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)
        assertTrue(
            "Should report trickle mode after updateIceMode",
            logs.any { it.message.contains("[trickle]") }
        )
        assertTrue(
            "Should NOT report standard mode",
            logs.none { it.message.contains("[standard]") }
        )
    }

    @Test
    fun `inbound call with standard ICE after previous trickle call reports standard`() {
        val previousCallId = UUID.randomUUID()

        // Previous call was trickle
        tracker.startCallTracking(previousCallId, isOutbound = true, useTrickleIce = true)
        tracker.completeCallTracking(previousCallId)

        // New inbound call starts - client field still has useTrickleIce=true from previous call
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = false, useTrickleIce = true)
        tracker.markInviteReceived(callId)

        // But user answers with standard ICE
        tracker.updateIceMode(callId, useTrickleIce = false)
        tracker.markAnswerInitiated(callId)

        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)
        assertTrue(
            "Should report standard mode after updateIceMode",
            logs.any { it.message.contains("[standard]") }
        )
    }

    // ===== Call is active milestone =====

    @Test
    fun `completeCallTracking includes Call is active row`() {
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = true, useTrickleIce = true)

        // Simulate some milestones
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)
        assertTrue(
            "Should include 'Call is active' step",
            logs.any { it.message.contains("Call is active") }
        )
    }

    // ===== Portal regex parsing =====

    @Test
    fun `long step name still has at least 2 spaces before delta`() {
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = true, useTrickleIce = true)

        // This milestone has a long step name: "First server-reflexive/relay candidate found" (44 chars)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_FIRST_SRFLX_RELAY_CANDIDATE)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)

        // Portal regex: (?<step>.+?)\s{2,}(?<delta>[\d.]+ms|-)
        val portalRegex = Pattern.compile("(?<step>.+?)\\s{2,}(?<delta>[\\d.]+ms|-)")

        // Check data rows (skip header, separator, and footer lines)
        val dataRows = logs.filter {
            it.message.contains("[CallTimings]") &&
                !it.message.contains("Step") &&
                !it.message.contains("---")
        }

        for (row in dataRows) {
            // Extract the part after the [CallTimings][direction][mode] prefix
            val prefixPattern = Pattern.compile("\\[CallTimings]\\[\\w+\\[\\w+]")
            val matcher = prefixPattern.matcher(row.message)
            if (matcher.find()) {
                val rowData = row.message.substring(matcher.end())
                assertTrue(
                    "Row data should match portal regex: '$rowData'",
                    portalRegex.matcher(rowData).find()
                )
            }
        }
    }

    @Test
    fun `large delta values still parse correctly`() {
        val callId = UUID.randomUUID()
        tracker.startCallTracking(callId, isOutbound = true, useTrickleIce = true)

        // Simulate milestones with large time gaps (just mark milestones in order)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        tracker.markCallMilestone(callId, LatencyTracker.MILESTONE_CALL_ACTIVE)

        val logs = tracker.completeCallTracking(callId)

        // Verify that delta values are properly formatted with ms suffix
        val dataRows = logs.filter {
            it.message.contains("[CallTimings]") &&
                !it.message.contains("Step") &&
                !it.message.contains("---") &&
                !it.message.contains("timing breakdown")
        }

        // Non-first rows should have delta values with "ms" suffix
        val nonFirstRows = dataRows.filter { !it.message.contains("Call Start") }
        for (row in nonFirstRows) {
            // Delta should be a number followed by "ms"
            val hasDeltaMs = row.message.contains(Regex("\\d+\\.\\d+ms"))
            assertTrue(
                "Non-first row should have delta with ms suffix: '${row.message}'",
                hasDeltaMs
            )
        }
    }
}
