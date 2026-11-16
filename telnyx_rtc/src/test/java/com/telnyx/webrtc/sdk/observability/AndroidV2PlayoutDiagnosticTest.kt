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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t029 regressions: bounded, change-deduped, call-owned playout observations.
 *
 * The diagnostic distinguishes (without values): no native media-playout row, a row that
 * never qualifies as audio (kind/mediaType), and a qualified row whose numeric playout
 * members are absent/non-numeric. Observations are emitted only when they change, carry
 * the owning call's ids, and a replacement peer starts a fresh observation.
 */
class AndroidV2PlayoutDiagnosticTest {

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

    private fun observations(frames: List<String>): List<JsonObject> =
        named(frames, "logs").mapNotNull { event ->
            val payload = event["payload"]!!.jsonObject
            if (payload["message"]?.jsonPrimitive?.content == "playout_stats_observation")
                payload["details"]!!.jsonObject else null
        }

    private fun report(vararg rows: Pair<String, Map<String, Any?>>): RTCStatsReport {
        fun row(id: String, type: String, members: Map<String, Any?>): RTCStats = mockk<RTCStats>().also {
            every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
        }
        val stats = rows.mapIndexed { index, (type, members) -> row("$type-$index", type, members) }
        return mockk<RTCStatsReport>().also { every { it.statsMap } returns stats.associateBy { s -> s.id } }
    }

    @Test fun `no media-playout row is an observation, not a capability verdict`() = runBlocking {
        val clock = SteppingClock(1_000)
        val hostClient = client("playout-absent")
        val capture = AndroidV2Capture(hostClient, clock = clock)
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-absent-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            sink.stats(token, report("inbound-rtp" to mapOf("kind" to "audio", "packetsReceived" to 12)))
            sink.stats(token, report("inbound-rtp" to mapOf("kind" to "audio", "packetsReceived" to 22)))
            val observations = observations(capture.capturedFrames())
            assertEquals(1, observations.size)
            val mediaPlayout = observations.single()["media_playout"]!!.jsonObject
            assertEquals(0.0, mediaPlayout["rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0.0, mediaPlayout["audio_qualified_rows"]!!.jsonPrimitive.double, 0.0)
            assertFalse(mediaPlayout.containsKey("qualifier"))
            assertFalse(mediaPlayout.containsKey("fields"))
            assertEquals("no media-playout row means the family is absent",
                false, observations.single()["families"]!!.jsonObject["media-playout"]!!.jsonPrimitive.boolean)
            assertEquals(true, observations.single()["families"]!!.jsonObject["inbound-rtp"]!!.jsonPrimitive.boolean)
        } finally { capture.shutdown() }
    }

    @Test fun `qualified row with numeric fields is observed with types only`() = runBlocking {
        val hostClient = client("playout-qualified")
        val capture = AndroidV2Capture(hostClient, clock = SteppingClock(1_000))
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-qualified-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            sink.stats(token, report(
                "media-playout" to mapOf("kind" to "audio", "totalSamplesCount" to 4800.0, "synthesizedSamplesDuration" to 0.5),
            ))
            val observation = observations(capture.capturedFrames()).single()
            val mediaPlayout = observation["media_playout"]!!.jsonObject
            assertEquals(1.0, mediaPlayout["rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, mediaPlayout["audio_qualified_rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(true, mediaPlayout["qualifier"]!!.jsonObject["kind"]!!.jsonObject["present"]!!.jsonPrimitive.boolean)
            assertEquals("string", mediaPlayout["qualifier"]!!.jsonObject["kind"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("number", mediaPlayout["fields"]!!.jsonObject["totalSamplesCount"]!!.jsonPrimitive.content)
            assertEquals("absent", mediaPlayout["fields"]!!.jsonObject["totalPlayoutDelay"]!!.jsonPrimitive.content)
            assertFalse("no stat values are ever reported",
                observation.toString().contains("4800"))
        } finally { capture.shutdown() }
    }

    @Test fun `unqualified row is distinguished from a missing row`() = runBlocking {
        val hostClient = client("playout-rejected")
        val capture = AndroidV2Capture(hostClient, clock = SteppingClock(1_000))
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-rejected-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            sink.stats(token, report(
                "media-playout" to mapOf("kind" to "video", "totalSamplesCount" to 1.0),
            ))
            val mediaPlayout = observations(capture.capturedFrames()).single()["media_playout"]!!.jsonObject
            assertEquals(1.0, mediaPlayout["rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0.0, mediaPlayout["audio_qualified_rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(true, mediaPlayout["qualifier"]!!.jsonObject["kind"]!!.jsonObject["present"]!!.jsonPrimitive.boolean)
        } finally { capture.shutdown() }
    }

    @Test fun `non-numeric and non-finite field values are typed, never echoed`() = runBlocking {
        val hostClient = client("playout-types")
        val capture = AndroidV2Capture(hostClient, clock = SteppingClock(1_000))
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-types-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            sink.stats(token, report(
                "media-playout" to mapOf(
                    "kind" to "audio",
                    "totalSamplesCount" to "not-a-number",
                    "synthesizedSamplesDuration" to Double.NaN,
                    "totalPlayoutDelay" to 0.25,
                ),
            ))
            val fields = observations(capture.capturedFrames()).single()["media_playout"]!!.jsonObject["fields"]!!.jsonObject
            assertEquals("string", fields["totalSamplesCount"]!!.jsonPrimitive.content)
            assertEquals("non-finite-number", fields["synthesizedSamplesDuration"]!!.jsonPrimitive.content)
            assertEquals("number", fields["totalPlayoutDelay"]!!.jsonPrimitive.content)
            assertFalse(fields.toString().contains("not-a-number"))
        } finally { capture.shutdown() }
    }

    @Test fun `later availability changes are reported and identical samples are deduplicated`() = runBlocking {
        val clock = SteppingClock(1_000)
        val hostClient = client("playout-late")
        val capture = AndroidV2Capture(hostClient, clock = clock)
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-late-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            // Early pre-media sample: no playout row yet.
            sink.stats(token, report("inbound-rtp" to mapOf("kind" to "audio")))
            clock.nowMs += 1_500
            // Later: the qualified row with numeric fields appears.
            sink.stats(token, report(
                "media-playout" to mapOf("kind" to "audio", "totalSamplesCount" to 480.0),
            ))
            clock.nowMs += 1_500
            // Identical observation: no new emission (no flood).
            sink.stats(token, report(
                "media-playout" to mapOf("kind" to "audio", "totalSamplesCount" to 960.0),
            ))
            val events = observations(capture.capturedFrames())
            assertEquals(2, events.size)
            assertEquals(0.0, events[0]["media_playout"]!!.jsonObject["rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, events[1]["media_playout"]!!.jsonObject["rows"]!!.jsonPrimitive.double, 0.0)
            assertEquals("number", events[1]["media_playout"]!!.jsonObject["fields"]!!.jsonObject["totalSamplesCount"]!!.jsonPrimitive.content)
            for (event in events) {
                assertFalse("values (not just types) must never be reported", event.toString().contains("480"))
            }
        } finally { capture.shutdown() }
    }

    @Test fun `alternating observations are hard-capped per call with a truthful cap notice`() = runBlocking {
        val clock = SteppingClock(1_000)
        val hostClient = client("playout-flop")
        val capture = AndroidV2Capture(hostClient, clock = clock)
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-flop-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            // 30 alternating observations would flood every tick without the hard cap.
            repeat(15) { round ->
                sink.stats(token, report("media-playout" to mapOf("kind" to "audio", "totalSamplesCount" to round * 10.0)))
                clock.nowMs += 500
                sink.stats(token, report("media-playout" to mapOf("kind" to "video", "totalSamplesCount" to round * 10.0)))
                clock.nowMs += 500
            }
            val events = named(capture.capturedFrames(), "logs")
            val observations = events.count { it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_stats_observation" }
            val notices = events.count { it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_observation_capped" }
            assertEquals("emissions stop at the per-call cap", 6, observations)
            assertEquals("exactly one truthful cap notice", 1, notices)
            val notice = events.first { it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_observation_capped" }
            assertEquals(6.0, notice["payload"]!!.jsonObject["details"]!!.jsonObject["cap"]!!.jsonPrimitive.double, 0.0)
            assertEquals(6.0, notice["payload"]!!.jsonObject["details"]!!.jsonObject["emitted"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `cap is retained across peer replacement`() = runBlocking {
        val clock = SteppingClock(1_000)
        val hostClient = client("playout-cap-persist")
        val capture = AndroidV2Capture(hostClient, clock = clock)
        try {
            val sink = hostClient.callReportV2!!
            val token = V2CaptureToken()
            sink.started(token, "playout-cap-call", false, false, true,
                V2CaptureOptions(false, false, false, 2, 5))
            // Distinct observations flip qualitatively (qualified vs unqualified row):
            // measured values are never part of the observation, so value-only changes dedupe.
            fun observed(qualified: Boolean) = report(
                "inbound-rtp" to mapOf("kind" to "audio"),
                "media-playout" to if (qualified) mapOf("kind" to "audio", "totalSamplesCount" to 10.0)
                else mapOf("kind" to "video", "totalSamplesCount" to 10.0),
            )
            // Exhaust the cap with distinct observations.
            repeat(8) { index ->
                sink.stats(token, observed(index % 2 == 0))
                clock.nowMs += 300
            }
            val before = named(capture.capturedFrames(), "logs").count {
                it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_stats_observation"
            }
            assertEquals(6, before)
            // Peer replacement resets the observation state but NOT the lifetime cap.
            sink.peerClosing(token, null)
            sink.stats(token, observed(true))
            clock.nowMs += 300
            val logs = named(capture.capturedFrames(), "logs")
            val observations = logs.count { it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_stats_observation" }
            val notices = logs.count { it["payload"]!!.jsonObject["message"]?.jsonPrimitive?.content == "playout_observation_capped" }
            assertEquals("no new observations after the lifetime cap is exhausted", 6, observations)
            assertEquals("the cap notice is never repeated", 1, notices)
        } finally { capture.shutdown() }
    }

    @Test fun `observations are call-owned and reset on peer replacement`() = runBlocking {
        val clock = SteppingClock(1_000)
        val hostClient = client("playout-owned")
        val capture = AndroidV2Capture(hostClient, clock = clock)
        try {
            val sink = hostClient.callReportV2!!
            val callA = V2CaptureToken(); val callB = V2CaptureToken()
            val qualified = report("media-playout" to mapOf("kind" to "audio", "totalSamplesCount" to 480.0))
            val empty = report("inbound-rtp" to mapOf("kind" to "audio"))
            sink.started(callA, "owned-a", false, false, true, V2CaptureOptions(false, false, false, 2, 5))
            sink.started(callB, "owned-b", false, false, true, V2CaptureOptions(false, false, false, 2, 5))
            sink.stats(callA, qualified)
            sink.stats(callB, empty)
            clock.nowMs += 1_500
            // Peer replacement on A: a fresh observation is recorded for the new peer.
            sink.peerClosing(callA, null)
            sink.stats(callA, qualified)
            val events = named(capture.capturedFrames(), "logs")
            val aObservations = events.filter { (it["ids"]?.jsonObject?.get("call_id") as? kotlinx.serialization.json.JsonPrimitive)?.content == "owned-a" }
            val bObservations = events.filter { (it["ids"]?.jsonObject?.get("call_id") as? kotlinx.serialization.json.JsonPrimitive)?.content == "owned-b" }
            assertEquals(2, aObservations.size)
            assertEquals(1, bObservations.size)
        } finally { capture.shutdown() }
    }
}
