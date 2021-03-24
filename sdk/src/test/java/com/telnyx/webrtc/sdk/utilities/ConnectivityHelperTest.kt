package com.telnyx.webrtc.sdk.utilities

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkRequest
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.Assert
import org.junit.Before
import org.junit.Test

class ConnectivityHelperTest : BaseTest() {

    @MockK
    private lateinit var context: Context

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
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
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns manager
        every { manager.registerNetworkCallback(any(), callback) } answers { registered = true }
        every { manager.unregisterNetworkCallback(callback) } answers { registered = false }

        // register
        ConnectivityHelper.registerNetworkStatusCallback(context, callback)

        Assert.assertEquals(registered, true)

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