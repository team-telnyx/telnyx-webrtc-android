package com.telnyx.webrtc.sdk.utilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

class ConnectivityHelperTest : BaseTest() {

    @MockK
    private var context: Context = Mockito.mock(Context::class.java)

    @MockK
    lateinit var connectivityHelper: ConnectivityHelper

    @MockK
    lateinit var connectivityManager: ConnectivityManager

    @MockK lateinit var activeNetwork: Network

    @MockK lateinit var capabilities: NetworkCapabilities

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true)

        BuildConfig.IS_TESTING.set(true)
    }

    /**
     * Test network callback behavior.
     */
    @Test
    fun testNetworkCallback() {
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
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.registerNetworkCallback(any(), callback) } just Runs
        every { connectivityManager.registerNetworkCallback(any(), callback) } answers { registered = true }
        every { connectivityManager.unregisterNetworkCallback(callback) } answers { registered = false }
        every { connectivityHelper.isNetworkEnabled(context) } returns true
        every { connectivityHelper.registerNetworkStatusCallback(context, callback) } just Runs

        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityHelper.isNetworkEnabled(context) } returns false
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false

        connectivityHelper.registerNetworkStatusCallback(context, callback)

        // register
        ConnectivityHelper.registerNetworkStatusCallback(context, callback)

        callback.onUnavailable()
        Assert.assertEquals(available, false)

        // network found
        callback.onAvailable(mockk())
        Assert.assertEquals(available, true)

        // loss of network
        callback.onLost(mockk())
        Assert.assertEquals(available, false)

        // unregister
        ConnectivityHelper.unregisterNetworkStatusCallback(context, callback)
        Assert.assertEquals(registered, false)
    }
}
