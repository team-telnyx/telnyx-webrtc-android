@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.telnyx.webrtc.sdk.*
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Test
import org.junit.Rule
import org.junit.Assert.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList

class AndroidV2TransportIngressTest : BaseTest() {
    @get:Rule val instant = InstantTaskExecutorRule()
    @Test fun `actual credential entrypoint logs into independent real telemetry socket with typed event`() = runBlocking {
        BuildConfig.IS_TESTING.set(true)
        val context = mockk<Context>(relaxed = true)
        val audio = mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns audio
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>(relaxed = true)
        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs
        val client = TelnyxClient(context)
        val signalingSocket = mockk<WebSocket>(relaxed = true)
        client.socket = TxSocket("fixture.invalid", 443)
        TxSocket::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client.socket, signalingSocket)
        TxSocket::class.java.getDeclaredField("reportingClient").apply { isAccessible = true }.set(client.socket, client)
        val frames = CopyOnWriteArrayList<JsonObject>()
        val event = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val tokenEvent = CountDownLatch(1)
        val rawSignaling = CountDownLatch(1)
        val closedBoth = CountDownLatch(2)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val server = MockWebServer()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { sockets.add(webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown(); closedBoth.countDown() }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.countDown(); closedBoth.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonObject
                frames.add(frame)
                if (frame["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry_login") {
                    webSocket.send(buildJsonObject { put("jsonrpc", "2.0"); put("id", frame["id"]!!); put("result", buildJsonObject {}) }.toString())
                }
                if (frame["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "signaling_message") rawSignaling.countDown()
                if (frame["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "login_started") {
                    event.countDown()
                    if (frame["params"]?.jsonObject?.get("login_type")?.jsonPrimitive?.content == "token") tokenEvent.countDown()
                }
            }
        }
        repeat(2) { server.enqueue(MockResponse().withWebSocketUpgrade(listener)) }
        server.start()
        val endpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        assertTrue(client.configureCallReportV2(CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = endpoint)))
        try {
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("real fixture must receive typed native login event", event.await(5, TimeUnit.SECONDS))
            assertTrue("actual TxSocket send ingress must capture a sanitized signaling frame", rawSignaling.await(5, TimeUnit.SECONDS))
            val login = frames.first { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry_login" }
            assertTrue(login["params"].toString().contains("fixture-user"))
            val telemetry = frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry" }
            assertTrue(telemetry.isNotEmpty())
            assertTrue(telemetry.none { it.toString().contains("fixture-secret") })
            val payload = telemetry.first { it["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content == "login_started" }
            assertEquals(client.callReportV2InstanceId, payload["params"]!!.jsonObject["ids"]!!.jsonObject["sdk_instance_id"]!!.jsonPrimitive.content)
            verify { signalingSocket.send(any<String>()) }
            val installed = client.callReportV2
            assertTrue(client.configureCallReportV2(CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = endpoint)))
            assertSame("equal configuration must preserve active ownership", installed, client.callReportV2)
            assertTrue(client.configureCallReportV2(CallReportV2Config(enabled = false)))
            assertNull(client.callReportV2)
            assertTrue("disable closes the authenticated telemetry socket", closed.await(5, TimeUnit.SECONDS))
            assertTrue(client.configureCallReportV2(CallReportV2Config(mirrorConsole = false, localEndpoint = endpoint)))
            client.tokenLogin(TokenConfig("fixture-token", null, null, null, null, null))
            assertTrue("token credential update must authenticate and upload a typed event", tokenEvent.await(5, TimeUnit.SECONDS))
            val logins = frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry_login" }
            assertEquals(2, logins.size)
            val sequences = frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry" }
                .map { it["params"]!!.jsonObject["sequence"]!!.jsonPrimitive.long }
            assertTrue("same SDK instance sequence must survive disable/re-enable", sequences.zipWithNext().all { (a, b) -> a < b })
            assertTrue(logins.last()["params"].toString().contains("fixture-token"))
            client.anonymousLogin("fixture-target")
            assertTrue("anonymous transition revokes the prior authenticated socket", closedBoth.await(5, TimeUnit.SECONDS))
            assertTrue(frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry" }
                .none { it["params"]?.jsonObject?.get("login_type")?.jsonPrimitive?.content == "anonymous" })
        } finally {
            client.callReportV2?.dispose()
            sockets.forEach { it.close(1000, null) }
            server.shutdown()
            unmockkObject(ConnectivityHelper)
        }
    }
}
