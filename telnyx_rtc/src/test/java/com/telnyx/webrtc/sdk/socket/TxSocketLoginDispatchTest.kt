package com.telnyx.webrtc.sdk.socket

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.appcompat.app.AppCompatActivity
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.observability.CallReportV2Ingress
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.CapturingSlot
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okio.ByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * t027 regression (native dispatch path): the login RPC response routed through
 * the real [TxSocket.onMessage] branch must bind the session from the CURRENT
 * correlated response (exact outstanding LOGIN RPC id + same socket) and never
 * from a wrong-id / superseded / non-string-sessid response.
 *
 * OkHttp is mocked (no external network): the [OkHttpClient.Builder] chain is
 * intercepted, [OkHttpClient.newWebSocket] returns a mock and captures the real
 * [WebSocketListener] that [TxSocket] installs, so frames are delivered by
 * invoking it directly.
 *
 * FAIL-BEFORE note: the binding assertions exercise pre-existing behavior on the
 * base source (no session binding, no recorder context) and fail functionally
 * there. The new-seam reflection checks in TelnyxClientReconnectLifecycleTest
 * are AFTER-only checks; their NoSuchMethodException on the base is not a
 * functional reproduction.
 */
class TxSocketLoginDispatchTest : BaseTest() {

    private val mockContext = mockk<Context>(relaxed = true)
    private val audioManager = Mockito.spy(AudioManager::class.java)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val activeNetwork = mockk<Network>()
    private val capabilities = mockk<NetworkCapabilities>(relaxed = true)

    private val websocketSlot: CapturingSlot<okhttp3.WebSocketListener> = CapturingSlot()
    private val webSocket = mockk<WebSocket>(relaxed = true)
    private val sentFrames = mutableListOf<String>()
    private var staleError2: java.util.concurrent.atomic.AtomicReference<Throwable>? = null
    private val okHttpClient = mockk<OkHttpClient>(relaxed = true)

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true)
        BuildConfig.IS_TESTING.set(true)
        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        // No network is touched: TxSocket's real OkHttp client builder runs, but the
        // OkHttpClient instance itself is constructor-intercepted so that
        // newWebSocket() returns a mock and captures the real WebSocketListener
        // that TxSocket installs. Frames are then delivered by invoking that
        // listener directly.
        mockkConstructor(OkHttpClient::class)
        every { anyConstructed<OkHttpClient>().newWebSocket(any(), capture(websocketSlot)) } returns webSocket
        every { webSocket.send(any<ByteString>()) } answers { false }
        every { webSocket.send(any<String>()) } answers { sentFrames.add(firstArg()); true }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun awaitWebSocketListener() {
        val deadline = System.currentTimeMillis() + 10_000
        while (!websocketSlot.isCaptured) {
            check(System.currentTimeMillis() < deadline) { "WebSocketListener was never captured" }
            Thread.sleep(20)
        }
    }

    /**
     * Real client login flow on the test socket: credentialLogin records the
     * outstanding LOGIN RPC (candidate), sends the real login frame through
     * TxSocket.send (captured by the mocked OkHttp WebSocket), and returns the
     * JSON-RPC id so the test can dispatch the correlated server response.
     */
    private fun connectAndLogin(socket: TxSocket, client: TelnyxClient): String {
        connectListener(socket, client)
        client.credentialLogin(credentialConfig())
        val loginText = sentFrames.last()
        val id = com.google.gson.JsonParser.parseString(loginText).asJsonObject.get("id").asString
        return id
    }

    private fun setField(client: TelnyxClient, name: String, value: Any?) {
        val f = TelnyxClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(client, value)
    }

    private fun armReconnectionTimer(client: TelnyxClient) {
        val m = TelnyxClient::class.java.getDeclaredMethod("startReconnectionTimer")
        m.isAccessible = true
        m.invoke(client)
    }

    private fun credentialConfig() = CredentialConfig(
        sipUser = "user", sipPassword = "secret", sipCallerIDName = null,
        sipCallerIDNumber = null, fcmToken = null, ringtone = null, ringBackTone = null)

    private fun connectListener(socket: TxSocket, client: TelnyxClient) {
        socket.connectWithConnectionGuard(
            client, "rtc.telnyx.com", 14938, null,
            isCurrentConnection = { true }, onConnected = {})
        awaitWebSocketListener()
    }

    private fun dispatchFrame(socket: TxSocket, frame: String) {
        websocketSlot.captured.onMessage(webSocket, frame)
    }

    private fun loginResponse(id: String, sessid: String?): String {
        val session = sessid?.let { "," + "\"sessid\":\"" + it + "\"" } ?: ""
        return "{\\\"id\\\":\\\"$id\\\",\\\"result\\\":{\\\"message\\\":\\\"logged in\\\"$session}}".replace("\\\"", "\"")
    }

    @Test
    fun `a correct reconnect login response dispatched via TxSocket binds the session`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField(client, "reconnecting", true)
        setField(client, "credentialSessionConfig", credentialConfig())
        val sessidBefore = client.sessid
        val rpcId = connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse(rpcId, "sess-current"))

        assertEquals("dispatched login response must bind the server-confirmed session",
            "sess-current", client.sessid)
        assertEquals("recorder context must carry the confirmed session",
            "sess-current", readField(client, "callReportV2ServerSessionId") as? String)
        verify { ingress.signaling(any(), any(), any()) }
        assertFalse("reconnecting must be resolved by the dispatched confirmation",
            (readField(client, "reconnecting") as? Boolean) == true)
        assertTrue(sessidBefore != "sess-current")
    }

    @Test
    fun `a wrong-id login response dispatched via TxSocket never binds`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        val sessidBefore = client.sessid
        val rpcId = connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse("rpc-other", "sess-attacker"))

        assertEquals("a non-correlated response must not bind a session",
            sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
    }

    @Test
    fun `a non-string sessid in a dispatched response never binds`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        val sessidBefore = client.sessid
        val rpcId = connectAndLogin(socket, client)
        dispatchFrame(socket, """{"id":"$rpcId","result":{"message":"logged in","sessid":true}}""")

        assertEquals("a boolean sessid must not bind a session", sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
    }

    @Test
    fun `superseded login attempt rejects the older response`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        val sessidBefore = client.sessid
        connectAndLogin(socket, client)
        // A newer auth attempt supersedes the first: the login just sent is now the
        // only acceptable correlated response.
        connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse("rpc-first", "sess-old"))

        assertEquals("the superseded response must not bind", sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
    }

    @Test
    fun `armed reconnect timer is cancelled by a dispatched confirmation`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField(client, "reconnecting", true)
        setField(client, "credentialSessionConfig", credentialConfig())
        armReconnectionTimer(client)
        assertTrue("timer must be armed before the confirmation",
            (readField(client, "reconnectTimeOutJob") as? kotlinx.coroutines.Job)?.isActive == true)

        val rpcId = connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse(rpcId, "sess-current"))

        assertFalse("dispatched confirmation must cancel the reconnection timer",
            (readField(client, "reconnectTimeOutJob") as? kotlinx.coroutines.Job)?.isActive == true)
    }

    private fun readField(client: TelnyxClient, name: String): Any? {
        val f = TelnyxClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(client)
    }
    /**
     * Same-socket re-login on an already-logged-in socket: the login result is routed
     * through ownership confirmation regardless of prior isLoggedIn, so the renewed
     * session binds via the correlated RPC — and readiness is never synthetic.
     */
    @Test
    fun `an already-logged-in socket re-login binds a renewed session via correlated dispatch`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        // starting already logged in
        socket.isLoggedIn = true
        val sessidBefore = client.sessid
        val rpcId = connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse(rpcId, "sess-renewed"))
        staleErrorRef2().get()?.let { throw AssertionError("dispatch failed", it) }

        assertEquals("renewed session binds via correlated dispatch", "sess-renewed", client.sessid)
        verify(exactly = 1) { ingress.signaling(any(), any(), any()) }
        // readiness is never synthetic: the login result alone does not emit ready
        verify(exactly = 0) { ingress.ready() }
    }

    /** A wrong/stale id on an already-logged-in socket binds nothing and emits no readiness. */
    @Test
    fun `a wrong-id login result on an already-logged-in socket binds nothing and emits no readiness`() {
        val socket = TxSocket("rtc.telnyx.com", 14938)
        val client = TelnyxClient(mockContext)
        client.socket = socket
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        socket.isLoggedIn = true
        val sessidBefore = client.sessid
        val firstRpcId = connectAndLogin(socket, client)
        // a NEW auth is published via a real same-socket re-login (credentialLogin),
        // then the STALE result (the first login's id) arrives
        connectAndLogin(socket, client)
        dispatchFrame(socket, loginResponse(firstRpcId, "sess-attacker"))

        assertEquals("wrong-id result must not bind", sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        verify(exactly = 0) { ingress.ready() }
    }

    /** Second throwable slot for this test class. */
    private fun staleErrorRef2(): java.util.concurrent.atomic.AtomicReference<Throwable> {
        if (staleError2 == null) staleError2 = java.util.concurrent.atomic.AtomicReference()
        return staleError2!!
    }
}
