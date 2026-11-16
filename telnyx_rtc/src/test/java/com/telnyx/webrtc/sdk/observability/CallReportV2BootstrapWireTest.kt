@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Wire-level bootstrap regressions for Call Report V2 environment reconfiguration.
 * Reproduces (frozen 33dcff77) that the SDK creation pair recorded by the original
 * telemetry owner never reaches an authenticated wire when the environment is
 * switched before the first credential authentication, and that an initially
 * disabled installation loses it the same way once enabled.
 */
class CallReportV2BootstrapWireTest : BaseTest() {
    private fun installable(context: Context, localEndpoint: String): TelnyxClient {
        BuildConfig.IS_TESTING.set(true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>(relaxed = true)
        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs
        every { ConnectivityHelper.isNetworkEnabled(any()) } returns false
        val client = TelnyxClient(context)
        // Constructor-equivalent bootstrap: original owner emits the creation pair.
        AndroidV2Installer.install(client, CallReportV2Config(localEndpoint = localEndpoint), constructing = true)
        client.callReportV2?.creationFinished(client.callReportV2CreationDurationMs)
        return client
    }

    private fun dispose(client: TelnyxClient) {
        client.callReportV2?.dispose()
    }

    private fun fixtureSignalingSocket(client: TelnyxClient) {
        val websocket = mockk<WebSocket>(relaxed = true)
        every { websocket.send(any<String>()) } returns true
        client.socket = TxSocket("fixture.invalid", 443)
        TxSocket::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client.socket, websocket)
        TxSocket::class.java.getDeclaredField("reportingClient").apply { isAccessible = true }.set(client.socket, client)
    }

    private suspend fun drainCapture(client: TelnyxClient): List<String> =
        client.callReportV2?.capturedFrames().orEmpty()

    private fun eventFrames(frames: CopyOnWriteArrayList<JsonObject>): List<JsonObject> =
        frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry" }

    private fun awaitWireTelemetry(frames: CopyOnWriteArrayList<JsonObject>, minCount: Int): List<JsonObject> {
        val deadline = System.currentTimeMillis() + 5_000
        var events = eventFrames(frames)
        while (events.size < minCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            events = eventFrames(frames)
        }
        return events
    }

    private fun eventParams(frame: JsonObject): JsonObject = frame["params"]!!.jsonObject
    private fun eventName(frame: JsonObject): String = eventParams(frame)["name"]!!.jsonPrimitive.content
    private fun eventSequence(frame: JsonObject): Long = eventParams(frame)["sequence"]!!.jsonPrimitive.long

    private val frameNames = CopyOnWriteArrayList<String>()

    private fun assertSingleCreationPair(wire: List<JsonObject>, originalStartedTimestamp: String, creationDurationMs: Long) {
        val started = wire.filter { eventName(it) == "sdk_creation_started" }
        val created = wire.filter { eventName(it) == "sdk_created" }
        assertEquals("sdk_creation_started must reach the authenticated wire exactly once", 1, started.size)
        assertEquals("sdk_created must reach the authenticated wire exactly once", 1, created.size)
        val startedIdx = wire.indexOfFirst { eventName(it) == "sdk_creation_started" }
        val createdIdx = wire.indexOfFirst { eventName(it) == "sdk_created" }
        assertTrue("sdk_creation_started must precede sdk_created on the wire", createdIdx > startedIdx)
        assertTrue("sequence must be monotonically preserved", eventSequence(created.first()) > eventSequence(started.first()))
        assertEquals(
            "sdk_creation_started must carry the original constructor timestamp",
            originalStartedTimestamp,
            eventParams(started.first())["timestamp"]!!.jsonPrimitive.content
        )
        assertEquals(
            "sdk_created must carry the original creation duration",
            creationDurationMs.toDouble(),
            eventParams(created.first())["payload"]!!.jsonObject["creation_duration_ms"]!!.jsonPrimitive.double,
            0.0
        )
        assertEquals(
            "exactly one telemetry login must reach the wire (single receiver socket)",
            1,
            frameNames.count { it == "telnyx_rtc.telemetry_login" }
        )
    }

    @Test fun `dev auto selection before first credential auth delivers creation pair to the wire exactly once`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
                val method = frame["method"]?.jsonPrimitive?.content
                if (method != null) frameNames.add(method)
                when (method) {
                    "telnyx_rtc.telemetry_login" -> {
                        frames.add(frame)
                        webSocket.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", frame["id"]!!); put("result", buildJsonObject { })
                        }.toString())
                        loginAccepted.countDown()
                    }
                    "telnyx_rtc.telemetry" -> frames.add(frame)
                    else -> {}
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val client = installable(context, server.url("/telemetry").toString().replaceFirst("http://", "ws://"))
        try {
            val ownerBefore = client.callReportV2!!
            val originalFrames = drainCapture(client)
            val originalStarted = originalFrames
                .map { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                .firstOrNull { it?.get("params")?.jsonObject?.get("name")?.jsonPrimitive?.content == "sdk_creation_started" }
            val originalStartedTimestamp = originalStarted!!["params"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content

            client.connect(TxServerConfiguration.development(), CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            org.junit.Assert.assertSame("env change must retain the reporting owner", ownerBefore, client.callReportV2)
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted on the loopback wire", loginAccepted.await(5, TimeUnit.SECONDS))
            val wire = awaitWireTelemetry(frames, minCount = 4)
            assertSingleCreationPair(wire, originalStartedTimestamp, client.callReportV2CreationDurationMs)
            val pairEnvironment = wire.first { eventName(it) == "sdk_creation_started" }["params"]!!
                .jsonObject["client"]!!.jsonObject["environment"]!!.jsonPrimitive.content
            assertEquals("wire frames must carry the retargeted environment", "development", pairEnvironment)
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }

    /**
     * Disabled→enabled is a Native extension: the pinned JS telemetry client is created once per
     * SDK session and `telemetry.enabled === false` at construction means no telemetry client
     * exists at all (pinned sender.ts create()); there is no app-driven disable→enable lifecycle
     * in JS. The replacement owner therefore reports future observations only: no historical
     * creation pair is replayed, and the first post-enable authenticated observation is the
     * positive wire marker.
     */
    @Test fun `disabled first installation then enabled reports future observations only`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
                val method = frame["method"]?.jsonPrimitive?.content
                if (method != null) frameNames.add(method)
                when (method) {
                    "telnyx_rtc.telemetry_login" -> {
                        frames.add(frame)
                        webSocket.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", frame["id"]!!); put("result", buildJsonObject { })
                        }.toString())
                        loginAccepted.countDown()
                    }
                    "telnyx_rtc.telemetry" -> frames.add(frame)
                    else -> {}
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val localEndpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val client = installable(context, localEndpoint)
        try {
            client.configureCallReportV2(CallReportV2Config(enabled = false))
            assertNull("disabled configuration must remove the reporting owner", client.callReportV2)
            client.configureCallReportV2(CallReportV2Config(enabled = true, development = true, localEndpoint = localEndpoint))
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted on the loopback wire", loginAccepted.await(5, TimeUnit.SECONDS))
            val wire = awaitWireTelemetry(frames, minCount = 1)
            assertTrue(
                "no historical creation pair may be replayed after disable/enable",
                wire.none {
                    val name = eventName(it)
                    name == "sdk_creation_started" || name == "sdk_created"
                }
            )
            assertTrue("a positive future marker must reach the authenticated wire", wire.any { eventName(it) == "login_started" })
            assertEquals("exactly one telemetry login (single receiver socket)", 1, frameNames.count { it == "telnyx_rtc.telemetry_login" })
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }
}
