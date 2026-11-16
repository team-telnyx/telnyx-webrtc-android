@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Focused regressions for the ordered, non-blocking Call Report V2 retarget request hook. */
class CallReportV2CaptureRetargetTest {
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

    private suspend fun drain(capture: AndroidV2Capture): List<String> = capture.capturedFrames()

    private fun replacementOwner(): CallReportV2Ingress = object : CallReportV2Ingress {
        override fun started(token: V2CaptureToken, callId: String, inbound: Boolean, reattach: Boolean, trickle: Boolean, options: V2CaptureOptions) {}
        override fun state(token: V2CaptureToken, state: com.telnyx.webrtc.sdk.model.CallState) {}
        override fun stats(token: V2CaptureToken, report: com.telnyx.webrtc.lib.RTCStatsReport) {}
        override fun loginType(type: String) {}
        override fun signaling(generation: Long, voiceSdkId: String?, sessionId: String?) {}
        override fun peerClosing(token: V2CaptureToken, report: com.telnyx.webrtc.lib.RTCStatsReport?) {}
    }

    private fun frameEnvironment(frame: String): String? = runCatching {
        Json.parseToJsonElement(frame).jsonObject["params"]!!
            .jsonObject["client"]!!.jsonObject["environment"]!!.jsonPrimitive.content
    }.getOrNull()

    @Test fun `retarget request applies through shared runtime before credentials`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val client = client("retarget-applies")
        val capture = AndroidV2Capture(client)
        try {
            var applied = 0
            var rejection: Throwable? = null
            client.callReportV2!!.retarget(
                CallReportV2Config(captureOnly = true, mirrorConsole = false, development = true),
                guard = { true },
                onApplied = { applied++ },
                onRejected = { rejection = it },
            )
            drain(capture)
            assertEquals("queue acceptance is not application; exactly one applied result", 1, applied)
            assertNull(rejection)
        } finally { capture.shutdown() }
    }

    @Test fun `retarget request is ordered before authenticate on the shared ingress`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val serverPre = MockWebServer()
        serverPre.start()
        val serverPost = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
                if (frame["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry_login") {
                    webSocket.send(buildJsonObject {
                        put("jsonrpc", "2.0"); put("id", frame["id"]!!); put("result", buildJsonObject { })
                    }.toString())
                    loginAccepted.countDown()
                }
            }
        }
        serverPost.enqueue(MockResponse().withWebSocketUpgrade(listener))
        serverPost.start()
        val client = client("retarget-ordering")
        val preEndpoint = serverPre.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val postEndpoint = serverPost.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val capture = AndroidV2Capture(client, CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = preEndpoint))
        try {
            var applied = 0
            client.callReportV2!!.retarget(
                CallReportV2Config(captureOnly = false, mirrorConsole = false, development = true, localEndpoint = postEndpoint),
                guard = { true },
                onApplied = { applied++ },
                onRejected = { throw it },
            )
            client.callReportV2!!.authenticate("sip_credentials", "fixture-user", "fixture-secret")
            assertTrue("telemetry login must land on the retargeted endpoint", loginAccepted.await(5, TimeUnit.SECONDS))
            drain(capture)
            assertEquals(1, applied)
            assertNull("the pre-retarget endpoint must never be contacted", serverPre.takeRequest(500, TimeUnit.MILLISECONDS))
        } finally {
            capture.shutdown()
            serverPre.shutdown()
            serverPost.shutdown()
        }
    }

    @Test fun `mode-changing retarget requests are refused without side effects`() = runBlocking {
        val client = client("retarget-refused-mode")
        val capture = AndroidV2Capture(client)
        try {
            var applied = 0
            var rejection: Throwable? = null
            client.callReportV2!!.retarget(
                CallReportV2Config(captureOnly = false, mirrorConsole = false),
                guard = { true },
                onApplied = { applied++ },
                onRejected = { rejection = it },
            )
            drain(capture)
            assertEquals(0, applied)
            assertTrue(rejection is IllegalArgumentException)
            client.callReportV2!!.log("info", "after-mode-refusal")
            val frames = drain(capture)
            assertTrue("owner must keep observing after a refusal", frames.any { it.contains("after-mode-refusal") })
        } finally { capture.shutdown() }
    }

    @Test fun `published runtime refuses environment retarget clearly`() = runBlocking {
        assumeFalse(NATIVE_V2_RETARGET_SUPPORTED)
        val client = client("retarget-refused-capability")
        val capture = AndroidV2Capture(client)
        try {
            var applied = 0
            var rejection: Throwable? = null
            client.callReportV2!!.retarget(
                CallReportV2Config(captureOnly = true, mirrorConsole = false, development = true),
                guard = { true },
                onApplied = { applied++ },
                onRejected = { rejection = it },
            )
            drain(capture)
            assertEquals(0, applied)
            assertTrue(rejection is UnsupportedOperationException)
        } finally { capture.shutdown() }
    }

    @Test fun `interface default rejects explicitly instead of silently dropping`() {
        val bare = replacementOwner()
        var applied = 0
        var rejection: Throwable? = null
        bare.retarget(CallReportV2Config(), guard = { true }, onApplied = { applied++ }, onRejected = { rejection = it })
        assertEquals(0, applied)
        assertTrue(rejection is UnsupportedOperationException)
    }

    @Test fun `retarget on a disposed owner is rejected once without fake success`() = runBlocking {
        val client = client("retarget-disposed")
        val capture = AndroidV2Capture(client)
        val sink = client.callReportV2!!
        capture.shutdown()
        var applied = 0
        var rejection: Throwable? = null
        sink.retarget(
            CallReportV2Config(captureOnly = true, mirrorConsole = false, development = true),
            guard = { true },
            onApplied = { applied++ },
            onRejected = { rejection = it },
        )
        assertEquals("a disposed owner can never apply the request", 0, applied)
        assertTrue("rejection must be reported exactly once", rejection != null)
    }

    @Test fun `replaced owner drops stale retarget request silently`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val client = client("retarget-replaced")
        val capture = AndroidV2Capture(client, CallReportV2Config(captureOnly = true, mirrorConsole = false))
        val originalSink = client.callReportV2!!
        try {
            var applied = 0
            var rejections = 0
            client.callReportV2 = replacementOwner()
            originalSink.retarget(
                CallReportV2Config(captureOnly = true, mirrorConsole = false, development = true),
                guard = { true },
                onApplied = { applied++ },
                onRejected = { rejections++ },
            )
            drain(capture)
            assertEquals("stale request on a replaced owner invokes neither callback", 0, applied)
            assertEquals(0, rejections)
        } finally { capture.shutdown() }
    }
}
