package com.telnyx.webrtc.sdk

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.appcompat.app.AppCompatActivity
import androidx.test.rule.GrantPermissionRule
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.json.JSONObject
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.Spy
import kotlin.test.assertEquals


@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class TelnyxClientTest : BaseTest() {

    @MockK
    private var mockContext: Context = Mockito.mock(Context::class.java)

    @MockK
    lateinit var connectivityHelper: ConnectivityHelper

    @MockK
    lateinit var connectivityManager: ConnectivityManager

    @MockK lateinit var activeNetwork: Network

    @MockK lateinit var capabilities: NetworkCapabilities

    @MockK lateinit var networkRequest: NetworkRequest

    @Spy
    private lateinit var socket: TxSocket

    @Spy
    lateinit var client: TelnyxClient

    @MockK
    lateinit var audioManager: AudioManager


    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_NETWORK_STATE,
    )


    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this, true, true, true)
        networkCallbackSetup()

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager

        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        client = TelnyxClient(mockContext)
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
        every { connectivityManager.registerNetworkCallback(any(), callback) } answers { registered = true }
        every { connectivityManager.unregisterNetworkCallback(callback) } answers { registered = false }
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns true
        every { connectivityHelper.registerNetworkStatusCallback(mockContext, callback) } just Runs

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every {connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns false
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        connectivityHelper.registerNetworkStatusCallback(mockContext, callback)
    }

    @Test
    fun `initiate connection`() {
        client.connect()
        assertEquals(client.isNetworkCallbackRegistered, true)
    }

    @Test
    fun `disconnect connection`() {
        client.disconnect()
        assertEquals(client.isNetworkCallbackRegistered, false)
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
        client.connect()
        assertEquals(
            client.socketResponseLiveData.getOrAwaitValue(),
            SocketResponse.error("No Network Connection")
        )
    }

    @Test
    fun `login with valid credentials - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        ))
        client.connect()

        val config = CredentialConfig(
            "OliverZimmerman6",
            "Welcome@6",
            "Test",
            "000000000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.credentialLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1)).send(any(SendingMessageBody::class.java))
        //Thread.sleep(6000)
        //Mockito.verify(client, Mockito.times(1)).onLoginSuccessful(jsonMock)
    }

    @Test
    fun `login with invalid credentials - login sent to socket and json received`() {
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
            LogLevel.ALL
        )
        client.credentialLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1)).send(any(SendingMessageBody::class.java))
        Mockito.verify(client, Mockito.times(0)).onLoginSuccessful(jsonMock)

    }

    @Test
    fun `login with valid token - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        ))

        client.connect()

        val config = TokenConfig(
            anyString(),
            "Oliver",
            "Welcome@6",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.tokenLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1)).send(dataObject = any(SendingMessageBody::class.java))
    }

    @Test
    fun `login with invalid token - login sent to socket and json received`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        ))

        client.connect()

        val config = TokenConfig(
            anyString(),
            "test",
            "00000",
            null,
            null,
            null,
            LogLevel.ALL
        )
        client.tokenLogin(config)

        val jsonMock = Mockito.mock(JsonObject::class.java)

        Thread.sleep(3000)
        Mockito.verify(client.socket, Mockito.times(1)).send(dataObject = any(SendingMessageBody::class.java))
        Mockito.verify(client, Mockito.times(0)).onLoginSuccessful(jsonMock)
    }

    @Test
    fun `get raw ringtone`() {
        client.getRawRingtone()
    }

    @Test
    fun `get raw ringback tone`() {
        client.getRawRingbackTone()
    }

}

object MockitoHelper {
    fun <T> anyObject(): T {
        Mockito.any<T>()
        return uninitialized()
    }
    @Suppress("UNCHECKED_CAST")
    fun <T> uninitialized(): T =  null as T
}


