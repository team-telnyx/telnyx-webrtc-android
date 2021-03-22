package com.telnyx.webrtc.sdk

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
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

    lateinit var client: TelnyxClient


    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this,true,true, true)
        networkCallbackSetup()

        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        client = TelnyxClient(socket, mockContext)
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
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
        every { manager.registerNetworkCallback(any(), callback) } just Runs
        every { manager.registerNetworkCallback(any(), callback) } answers { registered = true }
        every { manager.unregisterNetworkCallback(callback) } answers { registered = false }
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns true
        every { connectivityHelper.registerNetworkStatusCallback(mockContext, callback) } just Runs

        connectivityHelper.registerNetworkStatusCallback(mockContext, callback)
    }

    @Test
    fun `initiate connection`() {
        assertEquals(client.isNetworkCallbackRegistered, true)
    }

    @Test
    fun `disconnect connection`() {
        client.disconnect()
        assertEquals(client.isNetworkCallbackRegistered, false)
    }

    @Test
    fun `attempt connection without network`() {
        every { connectivityHelper.isNetworkEnabled(mockContext) } returns false
        client.connect()
        assertEquals(client.socketResponseLiveData.getOrAwaitValue(), SocketResponse.error("No Network Connection"))
    }

    @Test
    fun `get raw ringtone`() {
        client.getRawRingtone()
    }

    @Test
    fun `get raw ringback tone`() {
        client.getRawRingbackTone()
    }

    //ToDo do for instrumentation.
    /*
    @Test
    fun `do credential login with invalid credentials`() {
        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        val client = TelnyxClient(socket, mockContext)
        val invalidCredentialConfig =
            CredentialConfig("Invalid User", "invalid password", null, null, null, null)
        client.credentialLogin(invalidCredentialConfig)
        assertEquals(client.getSessionID(), null)
    }

    @Test
    fun `do credential login with valid credentials`() {
        networkCallbackSetup()
        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        val client = TelnyxClient(socket, mockContext)
        val validCredentialConfig =
            CredentialConfig("OliverZimmerman6", "Welcome@6", null, null, null, null)
        client.credentialLogin(validCredentialConfig)
        assertNotNull(client.getSessionID())
    }
     */
}


