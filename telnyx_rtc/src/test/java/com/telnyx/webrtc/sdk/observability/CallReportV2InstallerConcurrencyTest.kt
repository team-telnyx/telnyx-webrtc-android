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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Actual runtime/wire regressions for installer reconfiguration races, driven through the ordered
 * ingress with deterministic barriers. The mid-command window (between the retarget guard and its
 * onApplied callback) cannot be scheduled without a production hook, so both reachable interleavings
 * (superseded-at-guard, and Core-applied-then-restored) are covered with identical wire outcomes; the
 * unreachable interleaving is reported in the checkpoint rather than adding SDK hooks for tests.
 */
class CallReportV2InstallerConcurrencyTest : BaseTest() {
    private fun installable(context: Context, localEndpoint: String): TelnyxClient {
        BuildConfig.IS_TESTING.set(true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>(relaxed = true)
        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs
        every { ConnectivityHelper.isNetworkEnabled(any()) } returns false
        val client = TelnyxClient(context)
        AndroidV2Installer.install(
            client,
            CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = localEndpoint),
            constructing = true,
        )
        client.callReportV2?.creationFinished(client.callReportV2CreationDurationMs)
        return client
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

    private fun telemetryFrames(frames: CopyOnWriteArrayList<JsonObject>): List<JsonObject> =
        frames.filter { it["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry" }

    private fun wireEnvironment(frame: JsonObject): String? = runCatching {
        frame["params"]!!.jsonObject["client"]!!.jsonObject["environment"]!!.jsonPrimitive.content
    }.getOrNull()

    private fun wireName(frame: JsonObject): String? = runCatching {
        frame["params"]!!.jsonObject["name"]!!.jsonPrimitive.content
    }.getOrNull()

    private fun wireSequence(frame: JsonObject): Long = runCatching {
        frame["params"]!!.jsonObject["sequence"]!!.jsonPrimitive.long
    }.getOrDefault(0L)

    /** Accepts telemetry logins with the canonical logged-in result and records telemetry frames. */
    private fun telemetryReceiver(
        frames: CopyOnWriteArrayList<JsonObject>,
        loginAccepted: CountDownLatch,
    ) = object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = try { Json.parseToJsonElement(text).jsonObject } catch (_: Exception) { return }
            if (frame["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry_login") {
                frames.add(frame)
                webSocket.send(buildJsonObject {
                    put("jsonrpc", "2.0"); put("id", frame["id"]!!)
                    put("result", buildJsonObject { put("message", "logged in") })
                }.toString())
                loginAccepted.countDown()
            } else if (frame["method"]?.jsonPrimitive?.content == "telnyx_rtc.telemetry") {
                frames.add(frame)
            }
        }
    }

    private fun awaitTelemetryCount(frames: CopyOnWriteArrayList<JsonObject>, minCount: Int): List<JsonObject> {
        val deadline = System.currentTimeMillis() + 5_000
        var events = telemetryFrames(frames)
        while (events.size < minCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            events = telemetryFrames(frames)
        }
        return events
    }

    /** Waits until the original creation pair has fully arrived on the wire (bounded). */
    private fun awaitPairOnWire(frames: CopyOnWriteArrayList<JsonObject>): List<JsonObject> {
        val deadline = System.currentTimeMillis() + 5_000
        var events = telemetryFrames(frames)
        while (System.currentTimeMillis() < deadline) {
            events = telemetryFrames(frames)
            val hasStarted = events.any { wireName(it) == "sdk_creation_started" }
            val hasCreated = events.any { wireName(it) == "sdk_created" }
            if (hasStarted && hasCreated) return events
            Thread.sleep(25)
        }
        return events
    }

    /** The original constructor pair must reach the wire exactly once: sequences 1 and 2, in order. */
    private fun assertOriginalPairOnWire(wire: List<JsonObject>) {
        val started = wire.filter { wireName(it) == "sdk_creation_started" }
        val created = wire.filter { wireName(it) == "sdk_created" }
        assertEquals(1, started.size)
        assertEquals(1, created.size)
        assertEquals(1L, wireSequence(started.first()))
        assertEquals(2L, wireSequence(created.first()))
        assertTrue(wire.indexOfFirst { wireName(it) == "sdk_created" } > wire.indexOfFirst { wireName(it) == "sdk_creation_started" })
    }

    private fun originalStartedTimestamp(frames: List<String>): String? = frames
        .map { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        .firstOrNull { it?.get("params")?.jsonObject?.get("name")?.jsonPrimitive?.content == "sdk_creation_started" }
        ?.get("params")!!.jsonObject["timestamp"]!!.jsonPrimitive.content

    @Test fun `explicit equal config while auto retarget queued keeps wire on production`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(telemetryReceiver(frames, loginAccepted)))
        server.start()
        val endpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val client = installable(context, endpoint)
        try {
            val ownerBefore = client.callReportV2
            val originalTimestamp = originalStartedTimestamp(drainCapture(client))
            // auto dev retarget queued through the ordered ingress...
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            // ...and an explicit configuration equal to the applied config lands while it is
            // queued or in flight: supersede-at-guard, or apply-then-restore. Both reachable
            // interleavings converge to the same explicit final wire state.
            assertTrue(client.configureCallReportV2(
                CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = endpoint)))
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted", loginAccepted.await(10, TimeUnit.SECONDS))
            drainCapture(client)
            val wire = awaitPairOnWire(frames)
            assertOriginalPairOnWire(wire)
            wire.forEach { frame ->
                if (wireName(frame) != null) {
                    assertEquals("explicit final environment must hold on the wire", "production", wireEnvironment(frame))
                }
            }
            assertSame("owner must survive the configuration race", ownerBefore, client.callReportV2)
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            val startedOnWire = wire.first { wireName(it) == "sdk_creation_started" }
            assertEquals("original creation timestamp must reach the wire unchanged",
                originalTimestamp, startedOnWire["params"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content)
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }

    @Test fun `explicit config after core changed restores wire production with original pair`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(telemetryReceiver(frames, loginAccepted)))
        server.start()
        val endpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val client = installable(context, endpoint)
        try {
            val ownerBefore = client.callReportV2
            val originalTimestamp = originalStartedTimestamp(drainCapture(client))
            // auto dev retarget applied: the shared runtime actually changes before the explicit call
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            drainCapture(client)
            assertEquals("core state published after the shared retarget succeeded", true,
                client.callReportV2InstalledConfig?.development)
            // explicit production configuration restores core with a queued retarget (not a map edit)
            assertTrue(client.configureCallReportV2(
                CallReportV2Config(captureOnly = false, mirrorConsole = false, localEndpoint = endpoint)))
            drainCapture(client)
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted", loginAccepted.await(10, TimeUnit.SECONDS))
            drainCapture(client)
            val wire = awaitPairOnWire(frames)
            assertOriginalPairOnWire(wire)
            wire.forEach { frame ->
                if (wireName(frame) != null) {
                    assertEquals("restored environment must hold on the wire", "production", wireEnvironment(frame))
                }
            }
            assertSame("owner must survive the restoration", ownerBefore, client.callReportV2)
            val startedOnWire = wire.first { wireName(it) == "sdk_creation_started" }
            assertEquals("original creation timestamp must reach the wire unchanged",
                originalTimestamp, startedOnWire["params"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content)
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }

    @Test fun `post credential retarget refusal preserves owner config queue and wire`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(telemetryReceiver(frames, loginAccepted)))
        server.start()
        val endpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val client = installable(context, endpoint)
        try {
            val ownerBefore = client.callReportV2
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted", loginAccepted.await(10, TimeUnit.SECONDS))
            drainCapture(client)
            val before = telemetryFrames(frames).size
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            drainCapture(client)
            assertSame("refusal must preserve the owner", ownerBefore, client.callReportV2)
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            client.callReportV2?.log("info", "post-refusal-marker")
            val wire = awaitTelemetryCount(frames, before + 1)
            assertTrue("queue must still deliver to the original endpoint after refusal", wire.size > before)
            wire.forEach { frame ->
                if (wireName(frame) != null) {
                    assertEquals("wire environment must remain production after refusal",
                        "production", wireEnvironment(frame))
                }
            }
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }

    @Test fun `mirroring refusal preserves owner and original pair reaches loopback wire`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val loginAccepted = CountDownLatch(1)
        val frames = CopyOnWriteArrayList<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(telemetryReceiver(frames, loginAccepted)))
        server.start()
        val endpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
        val client = installable(context, endpoint)
        try {
            val ownerBefore = client.callReportV2
            val originalTimestamp = originalStartedTimestamp(drainCapture(client))
            assertEquals(false, client.configureCallReportV2(
                CallReportV2Config(captureOnly = false, mirrorConsole = true, localEndpoint = endpoint)))
            drainCapture(client)
            assertSame("mirroring refusal must preserve the owner", ownerBefore, client.callReportV2)
            assertEquals(false, client.callReportV2InstalledConfig?.mirrorConsole)
            fixtureSignalingSocket(client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue("telemetry login must be accepted", loginAccepted.await(10, TimeUnit.SECONDS))
            drainCapture(client)
            val wire = awaitPairOnWire(frames)
            assertOriginalPairOnWire(wire)
            val startedOnWire = wire.first { wireName(it) == "sdk_creation_started" }
            assertEquals("original creation timestamp must reach the wire unchanged",
                originalTimestamp, startedOnWire["params"]!!.jsonObject["timestamp"]!!.jsonPrimitive.content)
            assertEquals("unretargeted environment must hold on the wire", "production", wireEnvironment(startedOnWire))
        } finally {
            dispose(client)
            unmockkObject(ConnectivityHelper)
            server.shutdown()
        }
    }

    private fun dispose(client: TelnyxClient) {
        client.callReportV2?.dispose()
    }
}
