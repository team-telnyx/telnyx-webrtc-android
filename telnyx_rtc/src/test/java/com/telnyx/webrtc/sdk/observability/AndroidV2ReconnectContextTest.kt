@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.lib.RTCStats
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Clock
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t027 regression: reconnect lifecycle must preserve the recorder signaling
 * context (generation + voice_sdk_id), clear only the server-issued session id
 * until the CURRENT login response re-confirms it, and accept per-call metrics
 * as soon as the session is re-bound.
 *
 * Fail-before (pre-fix): the reconnect login cleared the whole context, so the
 * reconnect `login_started` carried socket_generation 0 and metrics stayed
 * refused for the rest of the session (see ROOT_CAUSE.md A).
 *
 * NOTE: authored as deterministic test source; NOT executed while the 8GiB
 * disk gate holds (see validation/t027/CHECKPOINT.md, TEST_PLAN.md).
 */
class AndroidV2ReconnectContextTest {

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

    private fun mediaReport(pairId: String = "pair-a", localAddress: String = "10.0.0.20"): RTCStatsReport {
        fun row(id: String, type: String, members: Map<String, Any>): RTCStats = mockk<RTCStats>().also {
            every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
        }
        val rows = listOf(
            row("transport", "transport", mapOf("selectedCandidatePairId" to pairId, "dtlsState" to "connected")),
            row(pairId, "candidate-pair", mapOf("localCandidateId" to "local", "remoteCandidateId" to "remote")),
            row("local", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "3412957284", "priority" to 41885695,
                "address" to localAddress, "port" to 57816)),
            row("remote", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "foundation" to "7f2a1b", "priority" to 41885694, "address" to "198.51.100.9", "port" to 17314)),
            row("codec", "codec", mapOf("mimeType" to "audio/opus", "clockRate" to 48000, "channels" to 2, "payloadType" to 111)),
            row("audio", "inbound-rtp", mapOf("kind" to "audio", "codecId" to "codec", "packetsReceived" to 652)),
            row("outgoing", "outbound-rtp", mapOf("kind" to "audio", "packetsSent" to 709, "bytesSent" to 70900)))
        return mockk<RTCStatsReport>().also { every { it.statsMap } returns rows.associateBy { r -> r.id } }
    }

    private fun paramsNamed(frames: List<String>, name: String) =
        params(frames).filter { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == name }

    @Test fun `reconnect login preserves generation and re-binds the session from the current response`() = runBlocking {
        val clock = SteppingClock(1_000)
        val mockClient = client("recon-context")
        every { mockClient.sessid } returns "sess-1"
        val capture = AndroidV2Capture(mockClient, clock = clock)
        try {
            val sink = mockClient.callReportV2!!
            // ---- First session -------------------------------------------------
            sink.socketStarting("wss://rtc.telnyx.com")            // reconnect = false
            sink.socketOpened("wss://rtc.telnyx.com")
            sink.authenticate("sip_credentials", "user", "secret") // initial login: clears context
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"rpc-1"}""")
            sink.signalingFrame("""{"id":"rpc-1","result":{"message":"logged in","sessid":"sess-1"}}""", false)
            sink.signaling(1L, "voice-1", "sess-1")                // gateway REGED binds the session
            sink.ready()
            val call1 = V2CaptureToken()
            sink.started(call1, "call-1", false, false, true, V2CaptureOptions(false, false, false, 2, 5))
            sink.state(call1, CallState.ACTIVE)
            clock.nowMs += 1_500
            sink.stats(call1, mediaReport())
            var metrics = paramsNamed(capture.capturedFrames(), "call_metrics")
            assertEquals("metrics accepted on the bound first session", 1, metrics.size)

            // ---- Socket drop and reconnect cycle ------------------------------
            sink.socketClosed(1006, "abnormal", failed = false)
            sink.socketStarting("wss://rtc.telnyx.com")            // reconnect = true (connectedOnce)
            sink.authenticate("sip_credentials", "user", "secret") // reconnect login: preserve gen/voice, clear session id
            val reconnectLogin = paramsNamed(capture.capturedFrames(), "login_started").last()
            // FAIL-BEFORE: pre-fix cleared the whole context (socket_generation 0 / absent).
            assertEquals("reconnect login_started must carry the live connection generation (t027 A3)",
                1.0, reconnectLogin["socket_generation"]?.jsonPrimitive?.double ?: -1.0, 0.0)
            assertEquals("resume_session_id is the sessid asked to resume (canonical events.ts:449)",
                "sess-1", reconnectLogin["payload"]!!.jsonObject["resume_session_id"]?.jsonPrimitive?.content ?: "<absent>")
            val idsBeforeConfirm = reconnectLogin["ids"]!!.jsonObject
            assertFalse("server-issued session id stays absent until the current login response confirms it",
                idsBeforeConfirm["session_id"] != null)

            // Samples before the session is re-confirmed stay refused (honest refusal):
            clock.nowMs += 1_500
            sink.stats(call1, mediaReport())
            val metricsWhileUnbound = paramsNamed(capture.capturedFrames(), "call_metrics")
            assertEquals("no new metrics while session_id is null", 1, metricsWhileUnbound.size)

            // The current correlated login response confirms the session (onLoginConfirmed):
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"rpc-2"}""")
            sink.signalingFrame("""{"id":"rpc-2","result":{"message":"logged in","sessid":"sess-2"}}""", false)
            sink.signaling(1L, "voice-2", "sess-2")
            sink.ready() // reconnect client_ready (canonical: every successful login)

            // A call after the reconnect records metrics again:
            val call2 = V2CaptureToken()
            sink.started(call2, "call-2", true, false, true, V2CaptureOptions(false, false, false, 2, 5))
            clock.nowMs += 1_500
            sink.stats(call2, mediaReport())
            metrics = paramsNamed(capture.capturedFrames(), "call_metrics")
            assertTrue("metrics accepted again once the session is re-bound", metrics.size >= 2)
            val ready = paramsNamed(capture.capturedFrames(), "client_ready")
            assertEquals("client_ready after the first connection AND every reconnect", 2, ready.size)
            assertTrue("reconnect client_ready is flagged as reconnect", ready.last()["payload"]!!.jsonObject["is_reconnect"]!!.jsonPrimitive.boolean)
        } finally { capture.shutdown() }
    }
}
