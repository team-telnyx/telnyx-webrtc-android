package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.GatewayState
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.*
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.rules.TestRule
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Spy
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class TelnyxClientTest : BaseTest() {

    @MockK
    private var mockContext: Context = Mockito.mock(Context::class.java)

    @MockK
    lateinit var connectivityHelper: ConnectivityHelper

    @MockK
    lateinit var connectivityManager: ConnectivityManager

    @MockK
    lateinit var activeNetwork: Network

    @MockK
    lateinit var capabilities: NetworkCapabilities

    private val testDispatcher = StandardTestDispatcher()
    @Spy
    private lateinit var socket: TxSocket

    @Spy
    lateinit var client: TelnyxClient

    @Spy
    lateinit var audioManager: AudioManager

    @get:Rule
    val rule: TestRule = InstantTaskExecutorRule()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true, true, true)
        networkCallbackSetup()

        Dispatchers.setMain(testDispatcher)

        BuildConfig.IS_TESTING.set(true)
        audioManager = Mockito.spy(AudioManager::class.java)

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager

        client = TelnyxClient(mockContext)
        client.connect(txPushMetaData = null)
    }

    private fun networkCallbackSetup() {
        var registered: Boolean? = null
        var available: Boolean? = null
        val callback = object : ConnectivityHelper.NetworkCallback() {
            override fun onNetworkAvailable() {
                available = true
            }

            override fun onNetworkUnavailable() {
                available = false
            }
        }
        mockkConstructor(NetworkRequest.Builder::class)
        mockkObject(NetworkRequest.Builder())
        val request = mockk<NetworkRequest>()
        val manager = mockk<ConnectivityManager>()
        every {
            anyConstructed<NetworkRequest.Builder>().addCapability(any()).addCapability(any())
                .build()
        } returns request
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.registerNetworkCallback(any(), callback) } just Runs
        every {
            connectivityManager.registerNetworkCallback(
                any(),
                callback
            )
        } answers { registered = true }
        every { connectivityManager.unregisterNetworkCallback(callback) } answers {
            registered = false
        }
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns true
        every { connectivityHelper.registerNetworkStatusCallback(mockContext, callback) } just Runs

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns false
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        connectivityHelper.registerNetworkStatusCallback(mockContext, callback)
    }

    @Test
    fun `initiate connection`() {
        client.connect(txPushMetaData = null)
        assertEquals(client.isNetworkCallbackRegistered, true)
    }

    @Test
    fun `checkForMockCredentials`() {
        assertEquals(MOCK_USERNAME_TEST, "<SIP_USER>")
        assertEquals(MOCK_PASSWORD, "<SIP_PASSWORD>")
    }


    @Test
    fun `disconnect connection`() {
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        client.onDisconnect()
        assertEquals(client.isNetworkCallbackRegistered, false)
        assertEquals(client.socket.isConnected, false)
        assertEquals(client.socket.isLoggedIn, false)
        assertEquals(client.socket.ongoingCall, false)
    }

    @Test
    fun `attempt connection without network`() {
        socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        client = Mockito.spy(TelnyxClient(mockContext))
        client.connect(txPushMetaData = null)
        assertEquals(
            client.socketResponseLiveData.getOrAwaitValue(),
            SocketResponse.error("No Network Connection")
        )
    }

    @Test
    fun `login with valid credentials - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val config = CredentialConfig(
            MOCK_USERNAME_TEST,
            MOCK_PASSWORD,
            "Test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)
        client.credentialLogin(config)


        Mockito.verify(client.socket, Mockito.times(1)).send(any(SendingMessageBody::class.java))
    }

    @Test
    fun `login with invalid credentials - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val config = CredentialConfig(
            "asdfasass",
            "asdlkfhjalsd",
            "test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)

        client.credentialLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1)).send(any(SendingMessageBody::class.java))
        Mockito.verify(client, Mockito.times(0)).onClientReady(jsonMock)
    }

    @Test
    fun `login with valid token - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )


        val config = TokenConfig(
            MOCK_TOKEN,
            "test",
            "000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)
        client.tokenLogin(config)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1))
            .send(dataObject = any(SendingMessageBody::class.java))
    }

    @Test
    fun `login with invalid token - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))


        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val config = TokenConfig(
            "",
            "test",
            "00000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)

        client.tokenLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)
        Mockito.verify(client.socket, Mockito.times(1))
            .send(dataObject = any(SendingMessageBody::class.java))
        Mockito.verify(client, Mockito.times(0)).onClientReady(jsonMock)
    }

    @Test
    fun `get raw ringtone`() {
        assertDoesNotThrow {
            client.getRawRingtone()
        }
    }

    @Test
    fun `get raw ringback tone`() {
        assertDoesNotThrow {
            client.getRawRingbackTone()
        }
    }

    @Test
    fun `try and send message when isConnected is false - do not connect before doing login`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val config = CredentialConfig(
            "asdfasass",
            "asdlkfhjalsd",
            "test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)

        client.credentialLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(2000)
        Mockito.verify(client, Mockito.times(0)).onClientReady(jsonMock)
    }

    @Test
    fun `login with credentials - No network available error thrown`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)
        val config = CredentialConfig(
            "asdfasass",
            "asdlkfhjalsd",
            "test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.credentialLogin(config)

        Thread.sleep(1000)
        assertEquals(
            client.socketResponseLiveData.getOrAwaitValue(),
            SocketResponse.error("Login Incorrect")
        )
    }

    @Test
    fun `Check login successful fires once REGED received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        val sessid = UUID.randomUUID().toString()
        client.onGatewayStateReceived(GatewayState.REGED.state, sessid)
        Mockito.verify(client, Mockito.times(1)).onLoginSuccessful(sessid)
    }

    @Test
    fun `Check gateway times out once NOREG is received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        val sessid = UUID.randomUUID().toString()
        client.onGatewayStateReceived(GatewayState.NOREG.state, sessid)
        assertEquals(
            client.socketResponseLiveData.getOrAwaitValue(),
            SocketResponse.error("Gateway registration has timed out")
        )
    }

    /*@Test
    fun `Check login reattempt if autoRetry set to true`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        ))
        client.connect()
        val config = CredentialConfig(
            "asdfasass",
            "asdlkfhjalsd",
            "test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL,
            true
        )
        client.credentialLogin(config)
        client.onGatewayStateReceived(GatewayState.FAIL_WAIT.state, null)
        Thread.sleep(9000)
        Mockito.verify(client, Mockito.atLeast(2)).onGatewayStateReceived(anyString(), anyString())
    }*/

    @Test
    fun `Test getSocketResponse returns appropriate LiveData`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        val socketResponse = client.getSocketResponse()
        assertEquals(socketResponse, client.socketResponseLiveData)
    }

    @Test
    fun `Test setting audio device to LOUDSPEAKER`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.setAudioOutputDevice(AudioDevice.LOUDSPEAKER)
        }
    }

    @Test
    fun `Test setting audio device to BLUETOOTH`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.setAudioOutputDevice(AudioDevice.BLUETOOTH)
        }
    }

    @Test
    fun `Test setting audio device to PHONE_EARPIECE`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.setAudioOutputDevice(AudioDevice.PHONE_EARPIECE)
        }
    }

    @Test
    fun `Test playing ringtone when one hasn't been set logs error and does not throw exception`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.playRingtone()
        }
    }

    @Test
    fun `Test playing ringBacktone when one hasn't been set logs error and does not throw exception`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.playRingtone()
        }
    }

    @Test
    fun `Test onErrorReceived posts LiveData to socketResponseLiveData`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        val errorJson = JsonObject()
        val errorMessageBody = JsonObject()
        errorMessageBody.addProperty("message", "my error message")
        errorJson.add("error", errorMessageBody)
        client.onErrorReceived(errorJson)
        assertEquals(
            client.socketResponseLiveData.getOrAwaitValue(),
            SocketResponse.error("my error message")
        )
    }

    @Test
    fun `Test onByeReceived calls call related method`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callId = UUID.randomUUID()
        client.onByeReceived(callId)
        Mockito.verify(client, Mockito.atLeast(1))?.onByeReceived(callId)
    }

    @Test
    fun `Test onAnswerReceived calls call related method`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        params.addProperty("callID", callID.toString())
        callMessage.add("params", params)
        client.onAnswerReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onAnswerReceived(callMessage)
    }

    @Test
    fun `Test onAnswerReceived customHeaders`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        fakeCall.callId = callID
        client.addToCalls(fakeCall)
        params.addProperty("callID", callID.toString())
        params.addProperty("sdp","sdp")
        val dialogParams = JsonObject()
        dialogParams.add("customHeaders", JsonArray().apply {
            add(JsonObject().apply {
                addProperty("X-key", "Value")
            })
        })
        params.add("dialogParams", dialogParams)
        callMessage.add("params", params)
        client.onAnswerReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onAnswerReceived(callMessage)
        assert(fakeCall.answerResponse != null)
    }



    @Test
    fun `Test onMediaReceived calls call related method`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        params.addProperty("callID", callID.toString())
        callMessage.add("params", params)
        client.onMediaReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onMediaReceived(callMessage)
    }

    @Test
    fun `Test onOfferReceived calls call related method`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        fakeCall.callId = callID
        params.addProperty("callID", callID.toString())
        callMessage.add("incorrect_params", params)
        client.providedTurn = "stun:rtc.telnyx.com"
        client.providedStun = "turn:rtc.telnyx.com"

        client.onOfferReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onOfferReceived(callMessage)
    }

    @Test
    fun `Test onRingingReceived calls call related method`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        params.addProperty("callID", callID.toString())
        params.addProperty("telnyx_session_id", callID.toString())
        params.addProperty("telnyx_leg_id", callID.toString())
        callMessage.add("params", params)
        client.onRingingReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onRingingReceived(callMessage)
    }

    @Test
    fun `Test onRingingReceived sets telnyx Session ID and leg ID within call`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        val fakeCall = Mockito.spy(Call(mockContext, client, client.socket, "", audioManager))
        Mockito.`when`(client.call).thenReturn(fakeCall)
        val callMessage = JsonObject()
        val params = JsonObject()
        val callID = UUID.randomUUID()
        fakeCall.telnyxSessionId = callID
        fakeCall.telnyxLegId = callID
        params.addProperty("callID", callID.toString())
        params.addProperty("telnyx_session_id", callID.toString())
        params.addProperty("telnyx_leg_id", callID.toString())
        callMessage.add("params", params)
        client.onRingingReceived(callMessage)
        Mockito.verify(client, Mockito.atLeast(1))?.onRingingReceived(callMessage)
        assertEquals(fakeCall.getTelnyxSessionId(), callID)
        assertEquals(fakeCall.getTelnyxLegId(), callID)
    }

    @Test
    fun `Test onRemoteSessionErrorReceived posts LiveData to socketResponseLiveData`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.onRemoteSessionErrorReceived("error")
        assertEquals(client.socketResponseLiveData.getOrAwaitValue(), SocketResponse.error("error"))
    }

    @Test
    fun `Test getting active calls returns empty mutable map`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        assertEquals(client.getActiveCalls(), mutableMapOf())
    }

    // Extension function for getOrAwaitValue for unit tests
    fun <T> LiveData<T>.getOrAwaitValue(
        time: Long = 10,
        timeUnit: TimeUnit = TimeUnit.SECONDS,
    ): T {
        var data: T? = null
        val latch = CountDownLatch(1)
        val observer = object : Observer<T> {
            override fun onChanged(value: T) {
                data = value
                latch.countDown()
                this@getOrAwaitValue.removeObserver(this)
            }
        }

        this.observeForever(observer)

        // Don't wait indefinitely if the LiveData is not set.
        if (!latch.await(time, timeUnit)) {
            throw TimeoutException("LiveData value was never set.")
        }

        @Suppress("UNCHECKED_CAST")
        return data as T
    }
}
