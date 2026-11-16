@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.RTCStats
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t029 regressions: audio-output metadata and effective ICE facts on call_started.
 *
 * Covers the observed Samsung gap: a selection measured before call creation, with no
 * in-call route change, used to leave call-level output label/count absent, and
 * call.options.ice_servers_count carried the requested server URL count (2) instead of
 * the effective peer configuration count including defaults (5).
 */
class AndroidV2OutputMetadataTest {

    private fun client(id: String): TelnyxClient {
        val client = mockk<TelnyxClient>(relaxed = true)
        var sink: CallReportV2Ingress? = null
        val sequence = java.util.concurrent.atomic.AtomicLong()
        every { client.nextCallReportV2Sequence() } answers { sequence.incrementAndGet() }
        every { client.callReportV2InstanceId } returns id
        every { client.callReportV2 } answers { sink }
        every { client.callReportV2 = any() } answers { sink = firstArg() }
        return client
    }

    private class SteppingClock(var nowMs: Long) : V2Clock {
        override fun monotonicMs() = nowMs
        override fun wallTimestamp() = "2026-11-16T00:00:00.000Z"
    }

    private fun params(frames: List<String>) = frames.map {
        Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject
    }

    private fun named(frames: List<String>, name: String) =
        params(frames).filter { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == name }

    private fun changedOf(event: kotlinx.serialization.json.JsonObject): List<String> =
        (event["payload"]!!.jsonObject["changed"] as? kotlinx.serialization.json.JsonArray)
            ?.map { it.jsonPrimitive.content }.orEmpty()

    private fun statsReport(dtlsState: String? = null): RTCStatsReport {
        fun row(id: String, type: String, members: Map<String, Any>): RTCStats = mockk<RTCStats>().also {
            every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
        }
        val rows = buildList {
            val transport = mutableMapOf<String, Any>("selectedCandidatePairId" to "pair-a")
            dtlsState?.let { transport["dtlsState"] = it }
            add(row("transport", "transport", transport))
            add(row("pair-a", "candidate-pair", mapOf("localCandidateId" to "local", "remoteCandidateId" to "remote")))
            add(row("local", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "f1", "priority" to 42, "address" to "10.0.0.2", "port" to 1)))
            add(row("remote", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "f2", "priority" to 41, "address" to "10.0.0.3", "port" to 2)))
            add(row("audio", "inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to 12)))
        }
        return mockk<RTCStatsReport>().also { every { it.statsMap } returns rows.associateBy { r -> r.id } }
    }

    /** configured = server URLs the client was configured with (provenance); effective = shared peer plan (5). */
    private fun startedOptions(customIce: Boolean = false, configured: Int = 2, effective: Int = 5) =
        V2CaptureOptions(false, false, customIce, configured, effective)

    @Test fun `selection observed before call seeds the first full snapshot with label and counts`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("seed-output"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false),
                V2AudioDevice("out2", "bluetooth", input = false)))
            sink.outputDevice(V2AudioDevice("out2", "bluetooth", input = false))
            sink.started(token, "seeded-call", false, false, true, startedOptions())
            sink.stats(token, statsReport())
            val snapshot = named(capture.capturedFrames(), "call_media_changed").single()
            val payload = snapshot["payload"]!!.jsonObject
            assertEquals("bluetooth", payload["output_device_label"]!!.jsonPrimitive.content)
            assertEquals("known inventory measures the output count", 2.0,
                payload["output_device_count"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, payload["input_device_count"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `unknown selection keeps the label out even when inventory is known`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("no-selection"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false)))
            sink.started(token, "unselected-call", false, false, true, startedOptions())
            sink.stats(token, statsReport())
            val payload = named(capture.capturedFrames(), "call_media_changed").single()["payload"]!!.jsonObject
            assertFalse("available devices never become a selected output", payload.containsKey("output_device_label"))
            assertEquals(1.0, payload["output_device_count"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `inventory never delivered keeps counts null and a known-empty inventory measures zero`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("empty-inventory"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "unknown-counts", false, false, true, startedOptions())
            sink.stats(token, statsReport())
            val unknown = named(capture.capturedFrames(), "call_media_changed").single()["payload"]!!.jsonObject
            assertFalse(unknown.containsKey("input_device_count"))
            assertFalse(unknown.containsKey("output_device_count"))
            assertFalse(unknown.containsKey("output_device_label"))

            clock.nowMs += 1_500
            sink.devices(emptyList())
            sink.stats(token, statsReport())
            val known = named(capture.capturedFrames(), "call_media_changed").last()["payload"]!!.jsonObject
            assertEquals("known-empty inventory is a measured 0", 0.0,
                known["input_device_count"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0.0, known["output_device_count"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `inventory change with the same selection updates counts without a route change`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("inventory-only"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false)))
            sink.outputDevice(V2AudioDevice("out1", "speaker", input = false))
            sink.started(token, "inventory-call", false, false, true, startedOptions())
            sink.stats(token, statsReport())
            val first = named(capture.capturedFrames(), "call_media_changed").single()
            assertEquals(1.0, first["payload"]!!.jsonObject["output_device_count"]!!.jsonPrimitive.double, 0.0)

            // Plugging in two extra outputs does NOT change the selected communication device:
            // no route change, but every call row must still carry the new measured count.
            clock.nowMs += 1_500
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false),
                V2AudioDevice("out2", "bluetooth", input = false),
                V2AudioDevice("out3", "usb", input = false)))
            sink.stats(token, statsReport())
            val updated = named(capture.capturedFrames(), "call_media_changed").last()
            val payload = updated["payload"]!!.jsonObject
            assertEquals("inventory change reaches the call row without a route change", 3.0,
                payload["output_device_count"]!!.jsonPrimitive.double, 0.0)
            assertEquals("speaker", payload["output_device_label"]!!.jsonPrimitive.content)
            assertTrue("output facts are marked with the contract vocabulary",
                changedOf(updated).contains("output_device"))
            assertFalse("unchanged input count is not marked", changedOf(updated).contains("input_device"))
            assertTrue("no duplicated route-change event was required",
                named(capture.capturedFrames(), "call_media_changed").size == 2)
        } finally { capture.shutdown() }
    }

    @Test fun `later full snapshots retain label and counts across media deltas`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("retain-facts"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false),
                V2AudioDevice("out2", "bluetooth", input = false)))
            sink.outputDevice(V2AudioDevice("out2", "bluetooth", input = false))
            sink.started(token, "retained-call", false, false, true, startedOptions())
            sink.stats(token, statsReport())
            clock.nowMs += 1_500
            sink.stats(token, statsReport(dtlsState = "connected"))
            val snapshots = named(capture.capturedFrames(), "call_media_changed")
            assertEquals(2, snapshots.size)
            val later = snapshots.last()["payload"]!!.jsonObject
            assertTrue(changedOf(snapshots.last()).contains("dtls_state"))
            assertEquals("retained label survives the media delta", "bluetooth",
                later["output_device_label"]!!.jsonPrimitive.content)
            assertEquals(2.0, later["output_device_count"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, later["input_device_count"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `concurrent call started after the selection receives current facts`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("concurrent"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val callA = V2CaptureToken(); val callB = V2CaptureToken()
            sink.devices(listOf(
                V2AudioDevice("in1", "microphone", input = true),
                V2AudioDevice("out1", "speaker", input = false),
                V2AudioDevice("out2", "bluetooth", input = false)))
            sink.started(callA, "concurrent-a", false, false, true, startedOptions())
            sink.stats(callA, statsReport())
            clock.nowMs += 1_500
            sink.outputDevice(V2AudioDevice("out2", "bluetooth", input = false))
            sink.started(callB, "concurrent-b", false, false, true, startedOptions())
            sink.stats(callB, statsReport())
            // No output_device_changed event is invented for B; the known selection simply
            // seeds B's snapshot, while A's retained facts keep flowing into A's snapshots.
            val startedB = named(capture.capturedFrames(), "call_started").filter {
                it["payload"]!!.jsonObject["options"]!!.jsonObject["is_reattach"]?.jsonPrimitive?.boolean == false
            }
            assertEquals(2, startedB.size)
            val bFacts = named(capture.capturedFrames(), "call_media_changed").last()["payload"]!!.jsonObject
            assertEquals("bluetooth", bFacts["output_device_label"]!!.jsonPrimitive.content)
            assertEquals(2.0, bFacts["output_device_count"]!!.jsonPrimitive.double, 0.0)
            clock.nowMs += 1_500
            sink.stats(callA, statsReport())
            val aLater = named(capture.capturedFrames(), "call_media_changed").firstOrNull {
                it["payload"]!!.jsonObject["output_device_label"]?.jsonPrimitive?.content == "bluetooth"
            }
            assertTrue("A's snapshot picks up the route change through the retained label",
                aLater != null)
        } finally { capture.shutdown() }
    }

    @Test fun `call_started separates requested from effective ICE facts`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("ice-facts"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "ice-call", false, false, true, startedOptions(customIce = true, configured = 2, effective = 5))
            val started = named(capture.capturedFrames(), "call_started").single()
            val payload = started["payload"]!!.jsonObject
            val options = payload["options"]!!.jsonObject
            assertEquals("the call used the effective peer configuration, defaults included", 5.0,
                options["ice_servers_count"]!!.jsonPrimitive.double, 0.0)
            assertEquals("provenance stays a flag, not a count", true,
                options["custom_ice_servers"]!!.jsonPrimitive.boolean)
            val extra = payload["extra"]!!.jsonObject
            assertEquals("the configured server-URL count stays separate from the effective count", 2.0,
                extra["ice_servers_configured_count"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }
}
