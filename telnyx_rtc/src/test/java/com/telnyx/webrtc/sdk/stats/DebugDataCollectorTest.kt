/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

import android.content.Context
import com.google.gson.JsonParser
import io.mockk.every
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DebugDataCollectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `reattach continues the existing call report`() {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporaryFolder.root
        val collector = DebugDataCollector(context)
        val callId = UUID.randomUUID()
        val originalSessionId = UUID.randomUUID()
        val originalLegId = UUID.randomUUID()
        val reattachedSessionId = UUID.randomUUID()
        val reattachedLegId = UUID.randomUUID()

        collector.onCallStarted(callId, originalSessionId, originalLegId)
        collector.updateCallMetadata(
            callId = callId,
            callerNumber = "caller",
            destinationNumber = "destination",
            direction = "outbound"
        )
        collector.addLogEntry(callId, "info", "before reattach")
        collector.recordIntervalStats(
            callId,
            IntervalStats(
                intervalStartUtc = 1_000L,
                intervalEndUtc = 2_000L,
                inboundPacketsReceived = 10L
            )
        )

        val resumedExistingReport = collector.onCallReattached(
            callId,
            reattachedSessionId,
            reattachedLegId
        )
        collector.addLogEntry(callId, "info", "after reattach")
        collector.recordIntervalStats(
            callId,
            IntervalStats(
                intervalStartUtc = 2_000L,
                intervalEndUtc = 3_000L,
                inboundPacketsReceived = 20L
            )
        )
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        assertTrue(resumedExistingReport)
        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val summary = report.getAsJsonObject("summary")
        val messages = report.getAsJsonArray("logs").map {
            it.asJsonObject.get("message").asString
        }

        assertEquals("outbound", summary.get("direction").asString)
        assertEquals("destination", summary.get("destinationNumber").asString)
        assertEquals(reattachedSessionId.toString(), report.get("telnyx_session_id").asString)
        assertEquals(reattachedLegId.toString(), report.get("telnyx_leg_id").asString)
        assertEquals(2, report.getAsJsonArray("stats").size())
        assertEquals(
            10L,
            report.getAsJsonArray("stats")[0]
                .asJsonObject.getAsJsonObject("audio")
                .getAsJsonObject("inbound")
                .get("packetsReceived").asLong
        )
        assertTrue("before reattach" in messages)
        assertTrue("Call reattached - continuing existing debug data" in messages)
        assertTrue("after reattach" in messages)
    }

    @Test
    fun `cold-start reattach initializes a call report`() {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporaryFolder.root
        val collector = DebugDataCollector(context)
        val callId = UUID.randomUUID()

        val resumedExistingReport = collector.onCallReattached(callId)
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        assertFalse(resumedExistingReport)
        assertTrue(java.io.File(requireNotNull(collector.getLastGeneratedJsonFilePath())).exists())
    }
}
