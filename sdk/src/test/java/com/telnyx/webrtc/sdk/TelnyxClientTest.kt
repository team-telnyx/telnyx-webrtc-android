package com.telnyx.webrtc.sdk

import android.Manifest
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.rule.GrantPermissionRule
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
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

    lateinit var client: TelnyxClient

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.ACCESS_NETWORK_STATE,
    )


    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this,true,true, true)
        networkCallbackSetup()

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager

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
        assertEquals(client.isNetworkCallbackRegistered, true)
    }

    @Test
    fun `disconnect connection`() {
        client.disconnect()
        assertEquals(client.isNetworkCallbackRegistered, false)
    }

    @Test
    fun `attempt connection without network`() {
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


