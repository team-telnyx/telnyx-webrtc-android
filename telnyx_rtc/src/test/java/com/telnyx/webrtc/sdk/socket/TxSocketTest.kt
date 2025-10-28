package com.telnyx.webrtc.sdk.socket

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import io.kotest.matchers.shouldBe
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals

class TxSocketTest : BaseTest() {
    @Test
    fun mockFailure() = runBlocking {
        val mock = MockEngine { call ->
            respond(
                "{}",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString())
            )
        }

        val client = HttpClient(mock) {
            install(WebSockets)
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
        }
        val resp = client.get<JsonObject>("dsf")
    }

    @MockK
    lateinit var connectivityHelper: ConnectivityHelper

    @MockK
    lateinit var connectivityManager: ConnectivityManager

    @MockK lateinit var activeNetwork: Network

    @MockK lateinit var capabilities: NetworkCapabilities

    @MockK
    lateinit var audioManager: AudioManager

    private lateinit var client: TelnyxClient

    @MockK
    private lateinit var mockContext: Context

    private lateinit var socket: TxSocket

    @Before
    fun setup() {
        MockKAnnotations.init(this, true)
        BuildConfig.IS_TESTING.set(true)
        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager

        networkCallbackSetup()
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
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns false
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        connectivityHelper.registerNetworkStatusCallback(mockContext, callback)
    }

    @Test
    fun `connect with valid host and port`() {
        BuildConfig.IS_TESTING.set(true)
        socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        client = spyk(TelnyxClient(mockContext))

        socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(6000)
        verify(exactly = 1) { client.onConnectionEstablished() }
        client.pingPong()
        Thread.sleep(30000)
        verify(atLeast = 2) { client.pingPong(any()) }
        socket.isPing shouldBe true
    }

    @Test
    fun `disconnect from socket`() {
        BuildConfig.IS_TESTING.set(true)
        client = spyk(TelnyxClient(mockContext))

        val testSocket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        client.socket = testSocket
        testSocket.connect(client)
        // Sleep to give time to connect
        Thread.sleep(1000)

        client.onDisconnect()
        Thread.sleep(1000)
        verify(atLeast = 1) { testSocket.destroy() }
    }

    @Test
    fun `connect with empty host or port - still connects with default`() {
        BuildConfig.IS_TESTING.set(true)
        socket = spyk(
            TxSocket(
                host_address = "",
                port = 0,
            )
        )

        client = spyk(TelnyxClient(mockContext))
        socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(6000)
        verify(exactly = 1) { client.onConnectionEstablished() }
    }

    @Test
    fun `set call to ongoing`() {
        BuildConfig.IS_TESTING.set(true)
        socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        socket.callOngoing()
        socket.ongoingCall shouldBe true
    }

    @Test
    fun `set call to not ongoing`() {
        BuildConfig.IS_TESTING.set(true)
        socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        socket.callNotOngoing()
        socket.ongoingCall shouldBe false
    }

    @Test
    fun `ensure connect changes isConnected appropriately`() {
        BuildConfig.IS_TESTING.set(true)
        socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        client = spyk(TelnyxClient(mockContext))
        socket.connect(client)
        Thread.sleep(6000)
        assertEquals(socket.isConnected, true)
    }

    @Test
    fun `make sure isConnected is false before connect is called`() {
        BuildConfig.IS_TESTING.set(true)
        client = spyk(TelnyxClient(mockContext))
        client.socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        assertEquals(client.socket.isConnected, false)
    }
}
