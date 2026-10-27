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

    @Test
    fun `ice diagnostics entries appear in the call report`() {
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
        val diagnostics = report.getAsJsonArray("iceDiagnostics")

        assertEquals(2, diagnostics.size())
        val first = diagnostics[0].asJsonObject
        assertEquals("ice_servers_config", first.get("event").asString)
        assertEquals("stun:stun.telnyx.com:3478", first.get("providedStun").asString)
        assertEquals(5, first.get("serverCount").asInt)
        val second = diagnostics[1].asJsonObject
        assertEquals("ice_connection_state", second.get("event").asString)
        assertEquals("CONNECTED", second.get("state").asString)
    }

    @Test
    fun `ice diagnostics cap keeps the report bounded`() {
        val collector = newCollector()
        val callId = UUID.randomUUID()

        collector.onCallStarted(callId)
        repeat(IceDiagnosticsCollector.MAX_ICE_DIAGNOSTICS_PER_CALL + 50) { index ->
            collector.onIceDiagnostic(callId, "candidate_generated", mapOf("seq" to index))
        }
        collector.onCallEnded(callId, "NORMAL_CLEARING")

        val reportPath = requireNotNull(collector.getLastGeneratedJsonFilePath())
        val report = JsonParser.parseString(java.io.File(reportPath).readText()).asJsonObject
        val diagnostics = report.getAsJsonArray("iceDiagnostics")

        assertEquals(IceDiagnosticsCollector.MAX_ICE_DIAGNOSTICS_PER_CALL, diagnostics.size())
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
        assertEquals(0, report.getAsJsonArray("iceDiagnostics").size())
    }

    @Test
    fun `turn url redaction strips userinfo credentials`() {
        assertEquals(
            "turn:host.telnyx.com:3478?transport=udp",
            IceDiagnosticsCollector.redactIceServerUrl("turn:user:pass@host.telnyx.com:3478?transport=udp")
        )
        assertEquals(
            "turns:host.telnyx.com:443?transport=tcp",
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
