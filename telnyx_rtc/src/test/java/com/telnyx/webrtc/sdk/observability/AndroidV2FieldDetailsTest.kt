@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.RTCStats
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.AudioCodec
import com.telnyx.webrtc.sdk.model.TelnyxWarningRegistry
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t027 regression (Phase B field details): executed capture/serialization checks for
 * - typed caller/name/destination metadata on call_started (outbound and inbound),
 * - sanitized app-supplied raw_call_options (no passwords/tokens/SDP; header NAMES only),
 * - requested codec names in canonical form and custom header names only,
 * - measured warning elapsed/threshold (never constants masquerading as measurements),
 * - device counts: unavailable (null) while the observer has never delivered an inventory
 *   vs measured 0 for a known-empty inventory,
 * - the subsequent full media snapshot retaining the latest measured counts.
 */
class AndroidV2FieldDetailsTest {

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
        override fun wallTimestamp() = "2026-11-15T00:00:00.000Z"
    }

    private fun params(frames: List<String>) = frames.map {
        Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject
    }

    private fun named(frames: List<String>, name: String) =
        params(frames).filter { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == name }

    private fun mediaReport(): RTCStatsReport {
        fun row(id: String, type: String, members: Map<String, Any>): RTCStats = mockk<RTCStats>().also {
            every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
        }
        val rows = listOf(
            row("transport", "transport", mapOf("selectedCandidatePairId" to "pair-a", "dtlsState" to "connected")),
            row("pair-a", "candidate-pair", mapOf("localCandidateId" to "local", "remoteCandidateId" to "remote")),
            row("local", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "f1", "priority" to 42, "address" to "10.0.0.2", "port" to 1)),
            row("remote", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "f2", "priority" to 41, "address" to "10.0.0.3", "port" to 2)),
            row("audio", "inbound-rtp", mapOf("kind" to "audio", "packetsReceived" to 12)))
        return mockk<RTCStatsReport>().also { every { it.statsMap } returns rows.associateBy { r -> r.id } }
    }

    private fun outboundOptions(): V2CaptureOptions {
        val raw = v2RawCallOptions(
            callerName = "Test Caller", callerNumber = "+15550001",
            destinationNumber = "+15550002", clientState = "app-state",
            customHeaderNames = listOf("X-Custom-One", "X-Custom-Two"),
            preferredCodecs = listOf("audio/opus/48000/1"))
        return V2CaptureOptions(forceRelay = false, prefetch = false, customIce = false, configuredIceServerCount = 2, effectiveIceServerCount = 5,
            callerName = "Test Caller", callerNumber = "+15550001", destinationNumber = "+15550002",
            rawCallOptions = raw,
            customHeaderNames = listOf("X-Custom-One", "X-Custom-Two"),
            preferredCodecs = listOf("audio/opus/48000/1"),
            useStereo = false)
    }

    @Test fun `call_started reports typed caller metadata and sanitized raw options`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("field-details"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "outbound-1", false, false, true, outboundOptions())
            val started = named(capture.capturedFrames(), "call_started").single()
            val payload = started["payload"]!!.jsonObject
            val options = payload["options"]!!.jsonObject
            assertEquals("+15550001", options["caller_number"]!!.jsonPrimitive.content)
            assertEquals("Test Caller", options["caller_name"]!!.jsonPrimitive.content)
            assertEquals("+15550002", options["destination_number"]!!.jsonPrimitive.content)
            assertEquals(false, options["use_stereo"]!!.jsonPrimitive.boolean)
            val codecs = options["preferred_codecs"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.jsonPrimitive.content }
            assertEquals(listOf("audio/opus/48000/1"), codecs)
            val headerNames = options["custom_header_names"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.jsonPrimitive.content }
            assertEquals(listOf("X-Custom-One", "X-Custom-Two"), headerNames)

            // raw_call_options: sanitized, no secrets/SDP; header names only
            val raw = payload["raw_call_options"]!!.jsonObject
            val rawKeys = raw.keys
            assertEquals(setOf("caller_name", "caller_number", "destination_number", "client_state",
                "custom_header_names", "preferred_codecs"), rawKeys)
            val rawHeaderNames = raw["custom_header_names"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.jsonPrimitive.content }
            assertEquals(listOf("X-Custom-One", "X-Custom-Two"), rawHeaderNames)
            val codecsRaw = raw["preferred_codecs"]!!.let { it as kotlinx.serialization.json.JsonArray }
                .map { it.jsonPrimitive.content }
            assertEquals(listOf("audio/opus/48000/1"), codecsRaw)
            assertTrue("no token/password/sdp keys in raw options",
                rawKeys.none { it.contains("token", true) || it.contains("passwd", true) || it.contains("sdp", true) || it.contains("password", true) })
            assertTrue("no header VALUES in raw options",
                raw.toString().contains("X-Custom-One") && !raw.toString().contains("value"))
        } finally { capture.shutdown() }
    }

    @Test fun `inbound call_started keeps raw options empty until the app supplies any`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("field-inbound"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            val inboundOptions = V2CaptureOptions(forceRelay = false, prefetch = false, customIce = false, configuredIceServerCount = 2, effectiveIceServerCount = 5,
                callerName = "Caller", callerNumber = "+15550009", destinationNumber = null)
            sink.started(token, "inbound-1", true, false, true, inboundOptions)
            val started = named(capture.capturedFrames(), "call_started").single()
            val payload = started["payload"]!!.jsonObject
            val options = payload["options"]!!.jsonObject
            assertEquals("+15550009", options["caller_number"]!!.jsonPrimitive.content)
            assertEquals("Caller", options["caller_name"]!!.jsonPrimitive.content)
            assertFalse("inbound call_started reports no requested codecs", options.containsKey("preferred_codecs"))
            assertEquals("answer-time app options are never back-filled into the earlier call_started", 0, payload["raw_call_options"]!!.jsonObject.keys.size)
        } finally { capture.shutdown() }
    }

    @Test fun `measured warning carries actual elapsed and threshold, unmeasured stays null`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("warning-measure"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "warn-call", false, false, true,
                V2CaptureOptions(false, false, false, 0, 5))
            // Measured: owned elapsed + configured threshold.
            sink.warning(TelnyxWarningRegistry.get(33002)!!, token,
                V2WarningMeasurement("ice_gathering_ms", 15234.0, 15000.0))
            // Unmeasured: no measurement object, fields stay null — never invented.
            sink.warning(TelnyxWarningRegistry.get(33001)!!, token)
            val warnings = named(capture.capturedFrames(), "call_warning")
            assertEquals(2, warnings.size)
            val measured = warnings.first { it["payload"]!!.jsonObject["metric"] != null }["payload"]!!.jsonObject
            assertEquals("ice_gathering_ms", measured["metric"]!!.jsonPrimitive.content)
            assertEquals(15234.0, measured["value"]!!.jsonPrimitive.double, 0.0)
            assertEquals(15000.0, measured["threshold"]!!.jsonPrimitive.double, 0.0)
            val unmeasured = warnings.first { it["payload"]!!.jsonObject["metric"] == null }["payload"]!!.jsonObject
            assertFalse(unmeasured.containsKey("metric"))
            assertFalse(unmeasured.containsKey("value"))
            assertFalse(unmeasured.containsKey("threshold"))
        } finally { capture.shutdown() }
    }

    @Test fun `device counts distinguish unavailable inventory from known-empty and persist in media snapshots`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("device-counts"); val capture = AndroidV2Capture(client, clock = clock)
        fun payloadOf(event: kotlinx.serialization.json.JsonObject) = event["payload"]!!.jsonObject
        fun dump(frames: List<kotlinx.serialization.json.JsonObject>): String = frames.joinToString("\n") { f ->
            (f["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() + " :: " + f["payload"]
        }
        try {
            val sink = client.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "device-call", false, false, true,
                V2CaptureOptions(false, false, false, 0, 5))
            // Observer has never delivered an inventory: count is null (unavailable), not 0.
            sink.inputTrack(token, enabled = true, ended = false, muted = false)
            val eventsBeforeInventory = named(capture.capturedFrames(), "call_media_changed")
            assertTrue("expected the input_device media event", eventsBeforeInventory.isNotEmpty())
            val before = payloadOf(eventsBeforeInventory.last())
            assertFalse("unavailable inventory reports no device count", before.containsKey("input_device_count"))

            // Observer delivered an empty inventory: count is a measured 0.
            sink.devices(emptyList())
            sink.inputTrack(token, enabled = true, ended = false, muted = false)
            val eventsAfterEmpty = named(capture.capturedFrames(), "call_media_changed")
            val emptyEvent = eventsAfterEmpty.map(::payloadOf).lastOrNull { it.containsKey("input_device_count") }
                ?: error("no input_device media event with a count after empty inventory; frames:\n" +
                    dump(named(capture.capturedFrames(), "call_media_changed")))
            assertEquals("known-empty inventory reports measured 0", 0.0,
                emptyEvent["input_device_count"]?.jsonPrimitive?.double
                    ?: error("missing input_device_count"), 0.0)

            // Observer delivered a populated inventory: measured counts.
            val inventory = listOf(
                V2AudioDevice("in1", "Built-in mic", input = true),
                V2AudioDevice("in2", "Headset mic", input = true),
                V2AudioDevice("out1", "Built-in speaker", input = false))
            sink.devices(inventory)
            sink.outputDevice(V2AudioDevice("out1", "Built-in speaker", input = false))
            val outputEvents = named(capture.capturedFrames(), "call_media_changed").map(::payloadOf)
            val outputEvent = outputEvents.lastOrNull { it.containsKey("output_device_count") }
                ?: error("no output_device media event with a count; frames:\n" +
                    dump(named(capture.capturedFrames(), "call_media_changed")))
            assertEquals("populated inventory reports measured output count", 1.0,
                outputEvent["output_device_count"]?.jsonPrimitive?.double
                    ?: error("missing output_device_count"), 0.0)

            // The next full media snapshot (stats tick) retains the latest measured counts and
            // the known output selection label. Because the device facts differ from the
            // previous snapshot (measured 0 counts, unknown selection), the snapshot marks them
            // changed with the contract vocabulary (input_device / output_device).
            sink.inputTrack(token, enabled = true, ended = false, muted = false)
            clock.nowMs += 1_500
            sink.stats(token, mediaReport())
            val snapshots = named(capture.capturedFrames(), "call_media_changed")
            val latestPayload = snapshots.last()["payload"]!!.jsonObject
            assertEquals("media snapshot retains measured input count", 2.0,
                latestPayload["input_device_count"]?.jsonPrimitive?.double
                    ?: error("missing input_device_count in snapshot; frames:\n" + dump(snapshots)), 0.0)
            assertEquals("media snapshot retains measured output count", 1.0,
                latestPayload["output_device_count"]?.jsonPrimitive?.double
                    ?: error("missing output_device_count in snapshot; frames:\n" + dump(snapshots)), 0.0)
            assertEquals("media snapshot retains the known output selection label", "Built-in speaker",
                latestPayload["output_device_label"]?.jsonPrimitive?.content
                    ?: error("missing output_device_label in snapshot; frames:\n" + dump(snapshots)))
            val latestChanged = (latestPayload["changed"] as? kotlinx.serialization.json.JsonArray)
                ?.map { c -> c.jsonPrimitive.content }.orEmpty()
            assertTrue("updated device facts are marked with the contract vocabulary",
                latestChanged.contains("output_device") && latestChanged.contains("input_device"))
        } finally { capture.shutdown() }
    }
}
