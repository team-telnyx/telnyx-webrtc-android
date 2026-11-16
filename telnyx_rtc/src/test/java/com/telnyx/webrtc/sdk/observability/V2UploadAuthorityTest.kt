@file:Suppress("FunctionNaming", "MagicNumber")
package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioManager
import com.google.gson.Gson
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.verto.send.*
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class V2UploadAuthorityTest {
    private fun client(): TelnyxClient {
        BuildConfig.IS_TESTING.set(true)
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        return TelnyxClient(context)
    }

    @Test fun `capture only and anonymous never authorize upload but authenticated target can resume`() = runBlocking {
        val client = client()
        val capture = AndroidV2Capture(client, CallReportV2Config(captureOnly = true, mirrorConsole = false))
        try {
            assertFalse(client.callReportV2!!.sendsToServerForLogin())
            assertTrue(client.callReportV2!!.collectsStats())
        } finally { capture.shutdown() }
        val uploader = AndroidV2Capture(client, CallReportV2Config(mirrorConsole = false))
        try {
            val sink = client.callReportV2!!
            sink.authenticate("anonymous", "target", "ai_assistant")
            assertFalse(sink.sendsToServer())
            assertTrue("new credential login evaluates target mode, not previous anonymous login", sink.sendsToServerForLogin())
            sink.authenticate("token", null, "fixture-token")
            assertTrue(sink.sendsToServer())
            sink.dispose()
            assertFalse(sink.sendsToServerForLogin())
        } finally { uploader.shutdown() }
    }

    @Test fun `login flags serialize false explicitly for disabled and anonymous clients`() {
        val gson = Gson()
        val normal = LoginParam(null, "fixture", "fixture", com.google.gson.JsonObject(), null, "session",
            sdkInstanceId = "owner", telemetryEnabled = true)
        val enabled = gson.toJsonTree(normal).asJsonObject
        assertTrue(enabled["telemetry_enabled"].asBoolean)
        assertEquals("owner", enabled["sdk_instance_id"].asString)
        assertFalse(gson.toJsonTree(normal.copy(telemetryEnabled = false)).asJsonObject["telemetry_enabled"].asBoolean)
        val anonymous = AnonymousLoginParams(targetId = "target", userAgent = UserAgent(), sdkInstanceId = "owner")
        assertFalse(gson.toJsonTree(anonymous).asJsonObject["telemetry_enabled"].asBoolean)
    }

    @Test fun `preauth server disable stops native collection and survives reporter replacement`() = runBlocking {
        assumeTrue("requires explicitly selected shared runtime candidate", NATIVE_V2_SERVER_GATE_SUPPORTED)
        val client = client()
        val server = MockWebServer()
        val closed = CountDownLatch(1)
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"jsonrpc":"2.0","method":"telnyx_rtc.telemetry_control","params":{"enabled":false}}""")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.countDown() // The adapter cancels, rather than waiting for a close handshake.
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason); closed.countDown()
            }
        }))
        server.start()
        val config = CallReportV2Config(mirrorConsole = false,
            localEndpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://"))
        val capture = AndroidV2Capture(client, config)
        try {
            val sink = client.callReportV2!!
            sink.authenticate("token", null, "fixture-token")
            assertTrue("server must close the actual native telemetry connection", closed.await(5, TimeUnit.SECONDS))
            // A queued barrier ensures the owner has seen the callback before assertions.
            capture.capturedFrames()
            assertTrue(client.callReportV2DisabledByServer.get())
            assertFalse(sink.collectsStats())
            assertFalse(sink.sendsToServerForLogin())
            sink.authenticate("sip_credentials", "fixture", "fixture")
            AndroidV2Installer.install(client, config.copy(captureOnly = true))
            assertNull("same SDK instance cannot bypass server latch by replacing reporter", client.callReportV2)
            AndroidV2Installer.install(client, config)
            assertNull(client.callReportV2)
            assertEquals(1, server.requestCount)
        } finally { capture.shutdown(); server.shutdown() }
    }
}
