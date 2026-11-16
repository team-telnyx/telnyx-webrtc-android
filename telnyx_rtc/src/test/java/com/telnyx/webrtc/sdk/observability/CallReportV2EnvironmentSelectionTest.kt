@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Endpoints
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.CallState
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CallReportV2EnvironmentSelectionTest : BaseTest() {
    private fun installable(context: Context, config: CallReportV2Config = CallReportV2Config()): TelnyxClient {
        BuildConfig.IS_TESTING.set(true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockk<ConnectivityManager>(relaxed = true)
        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs
        every { ConnectivityHelper.isNetworkEnabled(any()) } returns false
        val client = TelnyxClient(context)
        AndroidV2Installer.install(client, config, constructing = true)
        client.callReportV2?.creationFinished(client.callReportV2CreationDurationMs)
        return client
    }

    private suspend fun drainCapture(client: TelnyxClient): List<String> =
        client.callReportV2?.capturedFrames().orEmpty()

    private fun eventNames(frames: List<String>): List<String> = frames.mapNotNull { frame ->
        runCatching { Json.parseToJsonElement(frame).jsonObject["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content }
            .getOrNull()
    }

    @Test fun `dev signaling configuration selects the development telemetry endpoint`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            val ownerBefore = client.callReportV2
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            org.junit.Assert.assertSame("env change must retain the reporting owner", ownerBefore, client.callReportV2)
            drainCapture(client)
            assertEquals(
                "dev signaling must propagate to the telemetry environment",
                true,
                client.callReportV2InstalledConfig?.development
            )
            assertEquals(
                "telemetry endpoint must follow the shared environment selector",
                V2Endpoints.DEVELOPMENT,
                client.callReportV2InstalledConfig?.let { callReportV2Endpoint(it) }
            )
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `production signaling keeps production telemetry without prod-to-dev fallback`() {
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            client.connect(
                TxServerConfiguration.production(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            assertEquals(
                V2Endpoints.PRODUCTION,
                client.callReportV2InstalledConfig?.let { callReportV2Endpoint(it) }
            )
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `custom signaling host stays production unless explicitly configured for development`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            client.connect(
                TxServerConfiguration(host = "cst.example.com"),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            assertTrue(client.configureCallReportV2(CallReportV2Config(development = true)))
            drainCapture(client)
            client.connect(
                TxServerConfiguration(host = "cst.example.com"),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals(true, client.callReportV2InstalledConfig?.development)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `explicit telemetry configuration wins over derived signaling environment`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            assertTrue(client.configureCallReportV2(CallReportV2Config(development = true)))
            drainCapture(client)
            client.connect(
                TxServerConfiguration.production(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals(true, client.callReportV2InstalledConfig?.development)
            assertTrue(client.configureCallReportV2(CallReportV2Config()))
            drainCapture(client)
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals(false, client.callReportV2InstalledConfig?.development)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `repeated connects keep the reporting owner after the environment settles`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            drainCapture(client)
            val settledOwner = client.callReportV2
            assertEquals(true, client.callReportV2InstalledConfig?.development)
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertSame("repeat connects must not rebuild the reporting owner", settledOwner, client.callReportV2)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `shared endpoint selector follows environment and loopback override`() {
        assertEquals(V2Endpoints.PRODUCTION, callReportV2Endpoint(CallReportV2Config()))
        assertEquals(V2Endpoints.DEVELOPMENT, callReportV2Endpoint(CallReportV2Config(development = true)))
        assertEquals(
            "explicit loopback overrides must keep winning over the environment",
            "ws://127.0.0.1:1/telemetry",
            callReportV2Endpoint(CallReportV2Config(development = true, localEndpoint = "ws://127.0.0.1:1/telemetry"))
        )
    }

    @Test fun `every connect entry point propagates the development signaling environment`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        suspend fun assertDevelopmentEnvironment(expected: Boolean, body: () -> Unit) {
            body()
            drainCapture(client)
            assertEquals(expected, client.callReportV2InstalledConfig?.development)
        }
        try {
            val devConfig = TxServerConfiguration.development()
            val prodConfig = TxServerConfiguration.production()
            val credentials = CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)

            // legacy deprecated overload
            assertDevelopmentEnvironment(true) { client.connect(devConfig, null) }
            // credential overload
            assertDevelopmentEnvironment(true) { client.connect(devConfig, credentials, null) }
            // token overload
            assertDevelopmentEnvironment(true) {
                client.connect(devConfig, TokenConfig("fixture-token", null, null, null, null, null), null)
            }
            // anonymous overload
            assertDevelopmentEnvironment(true) { client.connectAnonymously(devConfig, "fixture-target") }
            // decline push overload
            assertDevelopmentEnvironment(true) { client.connectWithDeclinePush(devConfig, credentials) }

            // production stays production on every entry point
            client.connect(prodConfig, null)
            drainCapture(client)
            assertDevelopmentEnvironment(false) { client.connect(prodConfig, credentials, null) }
            assertDevelopmentEnvironment(false) {
                client.connect(prodConfig, TokenConfig("fixture-token", null, null, null, null, null), null)
            }
            assertDevelopmentEnvironment(false) { client.connectAnonymously(prodConfig, "fixture-target") }
            assertDevelopmentEnvironment(false) { client.connectWithDeclinePush(prodConfig, credentials) }
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `explicit disabled telemetry configuration survives connect entry points`() {
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            assertTrue(client.configureCallReportV2(CallReportV2Config(enabled = false)))
            assertNull(client.callReportV2)
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertNull("explicit disable must stay disabled", client.callReportV2)
            assertEquals(false, client.callReportV2InstalledConfig?.development)
            client.connect(
                TxServerConfiguration.development(),
                TokenConfig("fixture-token", null, null, null, null, null)
            )
            assertNull("explicit disable must stay disabled for token connects", client.callReportV2)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `explicit captureOnly and localEndpoint configurations survive connect entry points`() {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        server.start()
        val client = installable(context)
        try {
            val loopback = server.url("/telemetry").toString().replaceFirst("http://", "ws://")
            assertTrue(client.configureCallReportV2(CallReportV2Config(captureOnly = true, localEndpoint = loopback)))
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertEquals("explicit captureOnly must survive derived propagation", true, client.callReportV2InstalledConfig?.captureOnly)
            assertEquals("explicit loopback override must survive derived propagation", loopback,
                client.callReportV2InstalledConfig?.localEndpoint)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
            server.shutdown()
        }
    }

    @Test fun `environment change with an active call preserves the reporting owner and its lease`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            val owner = client.callReportV2!!
            val token = V2CaptureToken()
            owner.started(token, "call-active", false, false, false, V2CaptureOptions(false, false, false, 0, 5))
            drainCapture(client)
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            assertSame("an active call must pin the reporting owner", owner, client.callReportV2)
            owner.state(token, CallState.ACTIVE)
            val frames = drainCapture(client).map { Json.parseToJsonElement(it).jsonObject["params"]!!.jsonObject }
            val stateFrame = frames.last { it["name"]!!.jsonPrimitive.content == "call_state" }
            assertEquals("call-active", stateFrame["ids"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `environment change keeps exactly one SDK creation pair`() = runBlocking {
        assumeTrue(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            val framesBefore = drainCapture(client)
            val namesBefore = eventNames(framesBefore)
            assertEquals(1, namesBefore.count { it == "sdk_creation_started" })
            assertEquals(1, namesBefore.count { it == "sdk_created" })
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            val namesAfter = eventNames(drainCapture(client))
            assertEquals("the retargeted owner must keep the original pair, unreplaced", 1, namesAfter.count { it == "sdk_creation_started" })
            assertEquals(1, namesAfter.count { it == "sdk_created" })
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `capture-only console mirror never opens an upload socket`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        server.start()
        val client = installable(context)
        try {
            assertTrue(client.configureCallReportV2(CallReportV2Config(captureOnly = true, mirrorConsole = true,
                localEndpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://"))))
            client.callReportV2?.log("info", "mirror-capture")
            val frames = drainCapture(client)
            assertTrue("capture mode keeps frames locally", frames.any { it.contains("mirror-capture") })
            assertNull("capture-only mirror must not upload", server.takeRequest(1, TimeUnit.SECONDS))
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
            server.shutdown()
        }
    }

    @Test fun `console mirror prints captured frames even when the login is rejected`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val methods = CopyOnWriteArrayList<String>()
        val loginSeen = CountDownLatch(1)
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.parseToJsonElement(text).jsonObject
                val method = frame["method"]?.jsonPrimitive?.content
                if (method != null) methods.add(method)
                when (method) {
                    "telnyx_rtc.telemetry_login" -> {
                        webSocket.send(buildJsonObject {
                            put("jsonrpc", "2.0"); put("id", frame["id"]!!)
                            put("error", buildJsonObject { put("code", -32001); put("message", "login incorrect") })
                        }.toString())
                        loginSeen.countDown()
                    }
                    else -> {}
                }
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val client = installable(context, CallReportV2Config(captureOnly = false, mirrorConsole = true,
            localEndpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")))
        try {
            val websocket = mockk<WebSocket>(relaxed = true)
            every { websocket.send(any<String>()) } returns true
            client.socket = TxSocket("fixture.invalid", 443)
            TxSocket::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client.socket, websocket)
            TxSocket::class.java.getDeclaredField("reportingClient").apply { isAccessible = true }.set(client.socket, client)
            client.credentialLogin(CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null))
            assertTrue(loginSeen.await(5, TimeUnit.SECONDS))
            // exactly one telemetry login, zero event uploads; captured (mirrored) frames are not delivery
            assertEquals(1, methods.count { it == "telnyx_rtc.telemetry_login" })
            assertEquals(
                "telemetry events must not upload on rejected credentials",
                0,
                methods.count { it == "telnyx_rtc.telemetry" }
            )
            assertTrue(drainCapture(client).isNotEmpty())
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
            server.shutdown()
        }
    }

    @Test fun `anonymous-only session never uploads a telemetry frame`() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val server = MockWebServer()
        val sends = CopyOnWriteArrayList<String>()
        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                sends.add(text)
            }
        }
        server.enqueue(MockResponse().withWebSocketUpgrade(listener))
        server.start()
        val client = installable(context, CallReportV2Config(captureOnly = false, mirrorConsole = false,
            localEndpoint = server.url("/telemetry").toString().replaceFirst("http://", "ws://")))
        try {
            val websocket = mockk<WebSocket>(relaxed = true)
            every { websocket.send(any<String>()) } returns true
            client.socket = TxSocket("fixture.invalid", 443)
            TxSocket::class.java.getDeclaredField("webSocket").apply { isAccessible = true }.set(client.socket, websocket)
            TxSocket::class.java.getDeclaredField("reportingClient").apply { isAccessible = true }.set(client.socket, client)
            client.anonymousLogin("fixture-target")
            drainCapture(client)
            assertEquals("anonymous telemetry must never upload", 0, sends.size)
            assertNull(server.takeRequest(1, TimeUnit.SECONDS))
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
            server.shutdown()
        }
    }

    @Test fun `published runtime keeps production telemetry when dev signaling cannot retarget`() = runBlocking {
        assumeFalse(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            val ownerBefore = client.callReportV2
            client.connect(
                TxServerConfiguration.development(),
                CredentialConfig("fixture-user", "fixture-secret", null, null, null, null, null)
            )
            drainCapture(client)
            org.junit.Assert.assertSame("capability gaps must preserve the reporting owner", ownerBefore, client.callReportV2)
            assertEquals(
                "published runtime must keep the installed environment until explicitly reconfigured",
                false,
                client.callReportV2InstalledConfig?.development
            )
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }

    @Test fun `published runtime refuses explicit environment change without destroying the owner`() = runBlocking {
        assumeFalse(NATIVE_V2_RETARGET_SUPPORTED)
        val context = mockk<Context>(relaxed = true)
        val client = installable(context)
        try {
            val ownerBefore = client.callReportV2
            assertEquals("explicit environment changes must fail clearly on the published runtime",
                false, client.configureCallReportV2(CallReportV2Config(development = true)))
            drainCapture(client)
            org.junit.Assert.assertSame("refused retarget must preserve the owner", ownerBefore, client.callReportV2)
            assertEquals(false, client.callReportV2InstalledConfig?.development)
        } finally {
            unmockkObject(ConnectivityHelper)
            client.callReportV2?.dispose()
        }
    }
}
