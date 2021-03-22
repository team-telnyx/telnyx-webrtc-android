package com.telnyx.webrtc.sdk

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Rule
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class TelnyxClientTest : BaseTest() {

    @MockK
    private lateinit var client: TelnyxClient

    @MockK
    private var mockContext: Context = Mockito.mock(Context::class.java)

    @Rule @JvmField
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, true)
        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        client = TelnyxClient(socket, mockContext)
        //socketResponseLiveData.observeForever()

    }

    @org.junit.Test
    fun `do credential login with invalid credentials`() {
        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        client = TelnyxClient(socket, mockContext)
        val invalidCredentialConfig =
            CredentialConfig("Invalid User", "invalid password", null, null, null, null)
        client.credentialLogin(invalidCredentialConfig)
        assertEquals(client.socketResponseLiveData.getOrAwaitValue(), "Moobs")
    }

    @org.junit.Test
    fun `do credential login with valid credentials`() {
        val socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        client = TelnyxClient(socket, mockContext)
        val invalidCredentialConfig =
            CredentialConfig("OliverZimmerman6", "Welcome@6", null, null, null, null)
        client.credentialLogin(invalidCredentialConfig)
        assertEquals(client.socketResponseLiveData.getOrAwaitValue(), "Moobs")
    }
}

//Extension function for getOrAwaitValue for unit tests
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 2,
    timeUnit: TimeUnit = TimeUnit.SECONDS
): T {
    var data: T? = null
    val latch = CountDownLatch(1)
    val observer = object : Observer<T> {
        override fun onChanged(o: T?) {
            data = o
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


