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
import kotlin.test.assertTrue

class IceDiagnosticsCollectorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun newCollector(): DebugDataCollector {
        val context = mockk<Context>(relaxed = true)
        every { context.cacheDir } returns temporaryFolder.root
        return DebugDataCollector(context)
    }

    /**
     * The ICE diagnostics section is serialized as a NEW ITEM in the `stats`
     * array (not a top-level key), keyed by `iceDiagnostic`, so backend
     * whitelisting does not drop it.
     */
    private fun iceEvents(report: com.google.gson.JsonObject): List<com.google.gson.JsonObject> {
        val stats = report.getAsJsonArray("stats")
        for (element in stats) {
            val item = element.asJsonObject
            if (item.has("iceDiagnostic")) {
                return item.getAsJsonArray("iceDiagnostic").map { it.asJsonObject }
            }
        }
        throw AssertionError("stats array has no iceDiagnostic item")
    }
    private fun iceDiagnosticItem(report: com.google.gson.JsonObject): com.google.gson.JsonObject {
        val stats = report.getAsJsonArray("stats")
        for (element in stats) {
            val item = element.asJsonObject
            if (item.has("iceDiagnostic")) return item
        }
        throw AssertionError("stats array has no iceDiagnostic item")
    }

    @Test
    fun `ice diagnostics entries appear in the call report stats array`() {
        val collector = newCollector()
        val callId = UUID.randomUUID()

        collector.onCallStarted(callId)
        collector.onIceDiagnostic(
            callId,
            "ice_servers_config",
            mapOf("providedStun" to "stun:stun.telnyx.com:3478", "serverCount" to 5)
        )
        collector.onIceDiagnostic(
            callId,
            "ice_connection_state",
            mapOf("state" to "CONNECTED")
        )
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val item = iceDiagnosticItem(report)

        val diagnostics = item.getAsJsonArray("iceDiagnostic")
        assertEquals(2, diagnostics.size())
        val first = diagnostics[0].asJsonObject
        assertEquals("ice_servers_config", first.get("event").asString)
        assertEquals("stun:stun.telnyx.com:3478", first.get("providedStun").asString)
        assertEquals(5, first.get("serverCount").asInt)
        val second = diagnostics[1].asJsonObject
        assertEquals("ice_connection_state", second.get("event").asString)
        assertEquals("CONNECTED", second.get("state").asString)

        // Every entry carries the UTC wall-clock time of the event and the
        // offset from the call-start baseline.
        assertTrue(first.has("tsMs"), "ice event missing tsMs")
        assertTrue(first.has("msFromCallStart"), "ice event missing msFromCallStart")
        assertEquals(0L, first.get("msFromCallStart").asLong)
        val utcFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getTimeZone("UTC")
        }
        val firstTs = utcFormat.parse(first.get("tsMs").asString)!!.time
        val secondTs = utcFormat.parse(second.get("tsMs").asString)!!.time
        assertEquals(firstTs, secondTs - second.get("msFromCallStart").asLong)
    }

    @Test
    fun `ice diagnostics item is omitted from stats when empty`() {
        val collector = newCollector()
        val callId = UUID.randomUUID()

        collector.onCallStarted(callId)
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val stats = report.getAsJsonArray("stats")
        for (element in stats) {
            assertTrue(
                !element.asJsonObject.has("iceDiagnostic"),
                "empty iceDiagnostics must not emit an iceDiagnostic item"
            )
        }
    }

    @Test
    fun `all ice diagnostics entries are retained - no cap drops terminal state events`() {
        val collector = newCollector()
        val callId = UUID.randomUUID()

        collector.onCallStarted(callId)
        // Far more events than a normal call would ever produce
        repeat(300) { index ->
            collector.onIceDiagnostic(callId, "candidate_generated", mapOf("seq" to index))
        }
        // Terminal events arrive last and must survive
        collector.onIceDiagnostic(callId, "peer_connection_state", mapOf("state" to "FAILED"))
        collector.onIceDiagnostic(callId, "ice_connection_state", mapOf("state" to "FAILED"))
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val item = iceDiagnosticItem(report)
        val diagnostics = item.getAsJsonArray("iceDiagnostic")

        assertEquals(302, diagnostics.size())
        val last = diagnostics.last().asJsonObject
        assertEquals("ice_connection_state", last.get("event").asString)
        assertEquals("FAILED", last.get("state").asString)
        val secondToLast = diagnostics[diagnostics.size() - 2].asJsonObject
        assertEquals("peer_connection_state", secondToLast.get("event").asString)
        assertEquals("FAILED", secondToLast.get("state").asString)
    }

    @Test
    fun `diagnostics for an unknown call are ignored`() {
        val collector = newCollector()
        collector.onIceDiagnostic(UUID.randomUUID(), "ice_connection_state", mapOf("state" to "FAILED"))
        val callId = UUID.randomUUID()
        collector.onCallStarted(callId)
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val stats = report.getAsJsonArray("stats")
        var hasItem = false
        for (element in stats) {
            if (element.asJsonObject.has("iceDiagnostic")) hasItem = true
        }
        assertTrue(!hasItem, "entries recorded before onCallStarted must not leak into the report")
    }

    @Test
    fun `ice event timestamps are non-decreasing across entries`() {
        val collector = newCollector()
        val callId = UUID.randomUUID()

        collector.onCallStarted(callId)
        for (step in 0 until 5) {
            collector.onIceDiagnostic(callId, "candidate_generated", mapOf("seq" to step), tsMs = 1_000L + step * 100)
        }
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val events = iceEvents(report)
        assertEquals(5, events.size)
        val msFromStart = events.map { it.get("msFromCallStart").asLong }
        assertEquals(msFromStart, msFromStart.sorted())
    }

    @Test
    fun `turn url redaction strips userinfo credentials`() {
        assertEquals(
            "turn:user:<redacted>@host.telnyx.com:3478?transport=udp",
            IceDiagnosticsCollector.redactIceServerUrl("turn:user:pass@host.telnyx.com:3478?transport=udp")
        )
        assertEquals(
            "turns:user:<redacted>@host.telnyx.com:443?transport=tcp",
            IceDiagnosticsCollector.redactIceServerUrl("turns:user:secret@host.telnyx.com:443?transport=tcp")
        )
    }

    @Test
    fun `turn url redaction strips credential query params`() {
        assertEquals(
            "turn:host.telnyx.com:3478?username=<redacted>&credential=<redacted>",
            IceDiagnosticsCollector.redactIceServerUrl("turn:host.telnyx.com:3478?username=alice&credential=hunter2")
        )
    }

    @Test
    fun `stun urls and urls without credentials are unchanged`() {
        assertEquals("stun:stun.telnyx.com:3478", IceDiagnosticsCollector.redactIceServerUrl("stun:stun.telnyx.com:3478"))
        assertEquals("turn:host.telnyx.com:3478", IceDiagnosticsCollector.redactIceServerUrl("turn:host.telnyx.com:3478"))
        assertEquals("https://example.com", IceDiagnosticsCollector.redactIceServerUrl("https://example.com"))
    }
}
