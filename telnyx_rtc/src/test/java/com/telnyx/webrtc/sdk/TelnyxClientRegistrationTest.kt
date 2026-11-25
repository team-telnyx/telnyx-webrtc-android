package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.GatewayState
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.Region
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.registrationSocketUrl
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.send.LoginParam
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TelnyxClientRegistrationTest {
    @get:Rule
    val instantExecutor = InstantTaskExecutorRule()

    private lateinit var client: TelnyxClient
    private val gson = Gson()
    private val credentials = CredentialConfig("user", "test", "Caller", "1000", null, null, null)
    private val token = TokenConfig("test-token", "Caller", "1000", null, null, null)

    @Before
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.isNetworkEnabled(any()) } returns false
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs
        every { ConnectivityHelper.unregisterNetworkStatusCallback(any(), any()) } just Runs
        val context = mockk<Context>(relaxed = true)
        every { context.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        client = TelnyxClient(context)
    }

    @After
    fun tearDown() {
        if (::client.isInitialized) client.disconnect()
        unmockkObject(ConnectivityHelper)
        Dispatchers.resetMain()
    }

    @Test
    fun `explicit credential and token login omit session and attach fields`() {
        assertEquals("", client.sessid)
        client.sessid = "app-assigned"
        assertFresh(login { client.credentialLogin(credentials) })

        confirmRegistration("server-session", "server-voice")
        assertFresh(login { client.tokenLogin(token) })
        assertNull(state("confirmedSessionId"))
        assertNull(state("confirmedVoiceSdkId"))
    }

    @Test
    fun `push uses notification routing and next explicit login clears it across environments`() {
        val push = push("push-voice")
        client.connect(credentialConfig = credentials, txPushMetaData = gson.toJson(push))
        assertEquals(push, state("pushMetaData"))
        val pushLogin = login { client.credentialLogin(credentials) }
        assertFalse(pushLogin.has("sessid"))
        assertEquals("true", pushLogin.getAsJsonObject("loginParams").get("attach_call").asString)
        assertNull(state("confirmedVoiceSdkId"))

        confirmRegistration("push-session", "registered-voice")
        assertEquals("registered-voice", state("confirmedVoiceSdkId"))
        client.connect(providedServerConfig = TxServerConfiguration.development(), tokenConfig = token)
        assertEquals(TxServerConfiguration.development().host, client.socket.host_address)
        assertNull(state("pushMetaData"))
        assertNull(state("confirmedSessionId"))
        assertNull(state("confirmedVoiceSdkId"))
        assertFresh(login { client.tokenLogin(token) })
    }

    @Test
    fun `old timeout attach error bye and removed call cannot claim the next push`() {
        val first = push("first")
        client.connect(txPushMetaData = gson.toJson(first))
        confirmRegistration("first-session", "first-server-voice")
        val firstAttach = state("attachCallId") as String

        val second = push("second")
        client.connect(txPushMetaData = gson.toJson(second))
        val claim = TelnyxClient::class.java.getDeclaredMethod("claimPendingPushCall", String::class.java)
        claim.isAccessible = true
        assertNull(claim.invoke(client, firstAttach))
        client.onErrorReceived(JsonObject().apply { addProperty("id", firstAttach) }, null)
        var checks = 0
        client.onByeReceived(JsonObject().apply {
            add("params", JsonObject().apply { addProperty("callID", first.callId) })
        }) { ++checks == 1 }
        assertEquals(2, checks)
        client.removeFromCalls(UUID.fromString(first.callId))
        assertEquals(second, state("pushMetaData"))

        confirmRegistration("second-session", "second-server-voice")
        val secondAttach = state("attachCallId") as String
        assertEquals(UUID.fromString(second.callId), claim.invoke(client, secondAttach))
        assertNull(state("pushMetaData"))
        assertEquals("second-server-voice", state("confirmedVoiceSdkId"))
    }

    @Test
    fun `registration socket routes push and recovery independently`() {
        val host = "eu.example.test"
        val fresh = registrationSocketUrl(host, 8443, null, null)
        assertNull(fresh.queryParameter("voice_sdk_id"))
        val recovery = registrationSocketUrl(host, 8443, null, "server-voice")
        assertEquals(host, recovery.host)
        assertEquals(8443, recovery.port)
        assertEquals("server-voice", recovery.queryParameter("voice_sdk_id"))

        val push = registrationSocketUrl(host, 8443, push("notification-voice"), "server-voice")
        assertEquals("notification-voice", push.queryParameter("voice_sdk_id"))
        assertEquals(443, push.port)
        assertEquals("", registrationSocketUrl(host, 443, push(null), null).queryParameter("voice_sdk_id"))
    }

    @Test
    fun `gateway without a server session cannot make an app assigned id recoverable`() {
        client.socket = mockk(relaxed = true)
        client.sessid = "app-assigned"
        client.onGatewayStateReceived(GatewayState.REGED.state, null)
        client.onClientReady(JsonObject().apply { addProperty("voice_sdk_id", "server-voice") })
        assertNull(state("confirmedSessionId"))
        assertNull(state("confirmedVoiceSdkId"))
    }

    @Test
    fun `automatic reconnect sends the confirmed pair to the same endpoint and ignores old socket callbacks`() {
        val sockets = LinkedBlockingQueue<SignalingConnection>()
        mockkConstructor(OkHttpClient::class)
        try {
            every { anyConstructed<OkHttpClient>().newWebSocket(any(), any()) } answers {
                val connection = SignalingConnection(firstArg(), secondArg())
                sockets.add(connection)
                connection.socket
            }
            every { ConnectivityHelper.isNetworkEnabled(any()) } returns true
            val server = TxServerConfiguration("rtc.example.test", 8443)
            val config = token.copy(
                region = Region.EU,
                fallbackOnRegionFailure = false
            )
            client.connect(server, config)
            val initial = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            initial.open()
            assertFresh(initial.nextLogin())
            initial.receive("""{"method":"telnyx_rtc.clientReady","voice_sdk_id":"unconfirmed-voice"}""")
            initial.receive("""{"params":{"state":"DOWN"}}""")

            val retry = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            assertNull(retry.request.url.queryParameter("voice_sdk_id"))
            retry.open()
            assertFresh(retry.nextLogin())
            retry.receive("""{"method":"telnyx_rtc.clientReady","voice_sdk_id":"confirmed-voice"}""")
            retry.receive("""{"result":{"message":"logged in","sessid":"confirmed-session"}}""")
            retry.receive("""{"params":{"state":"REGED"}}""")
            assertEquals("confirmed-session", client.sessid)
            client.sessid = "app-assigned"

            assertTrue(client.requestCallRecoveryReattach(UUID.randomUUID()))
            val reconnect = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            assertEquals("eu.rtc.example.test", initial.request.url.host)
            assertEquals(initial.request.url.host, reconnect.request.url.host)
            assertEquals(8443, reconnect.request.url.port)
            assertEquals("confirmed-voice", reconnect.request.url.queryParameter("voice_sdk_id"))
            reconnect.open()
            val recoveryLogin = reconnect.nextLogin()
            assertEquals("confirmed-session", recoveryLogin.get("sessid").asString)
            assertEquals("true", recoveryLogin.getAsJsonObject("loginParams").get("attach_call").asString)

            val push = push("new-push")
            client.connect(server, config, gson.toJson(push))
            val latest = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            reconnect.receive("""{"result":{"sessid":"obsolete","params":{"state":"REGED"}}}""")
            reconnect.listener.onClosing(reconnect.socket, 1000, "closed")
            assertEquals(push, state("pushMetaData"))
            assertNull(state("confirmedSessionId"))
            assertEquals("new-push", latest.request.url.queryParameter("voice_sdk_id"))
            latest.open()
            val pushLogin = latest.nextLogin()
            assertFalse(pushLogin.has("sessid"))
            assertEquals("true", pushLogin.getAsJsonObject("loginParams").get("attach_call").asString)

            val declinePush = push("decline-push")
            client.connectWithDeclinePush(server, config, gson.toJson(declinePush))
            val decline = assertNotNull(sockets.poll(5, TimeUnit.SECONDS))
            assertEquals("decline-push", decline.request.url.queryParameter("voice_sdk_id"))
            decline.open()
            val declineLogin = decline.nextLogin()
            assertFalse(declineLogin.has("sessid"))
            val declineFlags = declineLogin.getAsJsonObject("loginParams")
            assertEquals(setOf("decline_push"), declineFlags.keySet())
            assertEquals("true", declineFlags.get("decline_push").asString)
        } finally {
            unmockkConstructor(OkHttpClient::class)
        }
    }

    private inner class SignalingConnection(val request: Request, val listener: WebSocketListener) {
        private val frames = LinkedBlockingQueue<String>()
        val socket = mockk<WebSocket>(relaxed = true) {
            every { send(any<String>()) } answers {
                frames.add(firstArg())
                true
            }
        }

        fun open() = listener.onOpen(socket, mockk(relaxed = true))

        fun receive(message: String) = listener.onMessage(socket, message)

        fun nextLogin(): JsonObject {
            val message = gson.fromJson(assertNotNull(frames.poll(5, TimeUnit.SECONDS)), JsonObject::class.java)
            assertEquals("login", message.get("method").asString)
            return message.getAsJsonObject("params")
        }
    }

    private fun confirmRegistration(session: String, voice: String) {
        client.socket = mockk(relaxed = true)
        client.onGatewayStateReceived(GatewayState.REGED.state, session)
        client.onClientReady(JsonObject().apply { addProperty("voice_sdk_id", voice) })
        assertEquals(session, client.sessid)
    }

    private fun login(send: () -> Unit): JsonObject {
        val socket = mockk<TxSocket>(relaxed = true)
        val message = io.mockk.slot<SendingMessageBody>()
        client.socket = socket
        send()
        verify { socket.send(capture(message)) }
        assertTrue(message.captured.params is LoginParam)
        return gson.toJsonTree(message.captured.params).asJsonObject
    }

    private fun assertFresh(params: JsonObject) {
        assertFalse(params.has("sessid"))
        assertFalse(params.has("loginParams"))
    }

    private fun state(name: String): Any? = TelnyxClient::class.java.getDeclaredField(name).run {
        isAccessible = true
        get(client)
    }

    private fun push(voice: String?) = PushMetaData("Caller", "1000", UUID.randomUUID().toString(), voice)
}
