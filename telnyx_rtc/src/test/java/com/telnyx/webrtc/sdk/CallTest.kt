package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.ktor.client.features.websocket.*
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.spyk
import io.mockk.mockkObject
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.ConnectivityManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.rules.TestRule
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.Spy
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class CallTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    lateinit var mockConnectivityManager: ConnectivityManager

    @Spy
    lateinit var client: TelnyxClient

    @Spy
    lateinit var socket: TxSocket

    @Spy
    lateinit var call: Call

    @MockK
    lateinit var audioManager: AudioManager

    @get:Rule
    val rule: TestRule = InstantTaskExecutorRule()

    @Before
    fun setup() {
        MockKAnnotations.init(this, true, true, true)

        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns mockConnectivityManager

        mockkObject(ConnectivityHelper)
        every { ConnectivityHelper.registerNetworkStatusCallback(any(), any()) } just Runs

        socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )

        BuildConfig.IS_TESTING.set(true)

        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager
        every { audioManager.isMicrophoneMute = true } just Runs
        every { audioManager.isSpeakerphoneOn = true } just Runs
        every { audioManager.isSpeakerphoneOn } returns false
        every { audioManager.isMicrophoneMute } returns false
        every { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION } just Runs

        client = spyk(
            TelnyxClient(
                mockContext
            )
        )
    }

    @Test
    fun `test call listen doesn't throw exception`() {
        assertDoesNotThrow {
            val newCall = Call(
                mockContext,
                client,
                socket,
                "123",
                audioManager
            )
        }
    }

    @Test
    fun `test ending call resets our call options`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        newCall.endCall(UUID.randomUUID())
        assertEquals(newCall.getIsMuteStatus().getOrAwaitValue(), false)
        assertEquals(newCall.getIsOnHoldStatus().getOrAwaitValue(), false)
        assertEquals(newCall.getIsOnLoudSpeakerStatus().getOrAwaitValue(), false)
    }

    @Test
    fun `test mute pressed during call`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        newCall.endCall(UUID.randomUUID())
        newCall.onMuteUnmutePressed()
        assertEquals(newCall.getIsMuteStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test hold pressed during call`() {
        val newCall = Call(mockContext, client, client.socket, "123", audioManager)
        client.socket.connect(client)

        // Sleep to give time to connect
        Thread.sleep(3000)
        newCall.onHoldUnholdPressed(UUID.randomUUID())
        assertEquals(newCall.getIsOnHoldStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test loudspeaker pressed during call`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        newCall.onLoudSpeakerPressed()
        assertEquals(newCall.getIsOnLoudSpeakerStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test new call is added to calls map - then assert that remove`() {
        socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val newCall = Mockito.spy(Call(mockContext, client, socket, "123", audioManager))
        newCall.callId = UUID.randomUUID()
        client.addToCalls(newCall)
        assert(client.calls.containsValue(newCall))

        client.removeFromCalls(newCall.callId)
        assert(!client.calls.containsValue(newCall))
    }

    @Test
    fun `test dtmf pressed during call with value 2`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        client.socket.connect(client)
        Thread.sleep(3000)


        call = Mockito.spy(
            Call(mockContext, client, client.socket, "123", audioManager)
        )
        call.dtmf(UUID.randomUUID(), "2")
        Thread.sleep(1000)
        Mockito.verify(client.socket, Mockito.times(1))
            .send(ArgumentMatchers.any(SendingMessageBody::class.java))
    }

    @Test
    fun `test new acceptCall from call where no SDP is contained`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        call = Mockito.spy(
            Call(mockContext, client, client.socket, "123", audioManager)
        )
        call.callId = UUID.randomUUID()
        client.addToCalls(call)
        call.acceptCall(call.callId, "00000")
        assertEquals(call.callStateFlow.value, CallState.ERROR)
    }

    @Test
    fun `Test call state returns callStateLiveData`() {
        client = Mockito.spy(TelnyxClient(mockContext))
        client.socket = Mockito.spy(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        call = Mockito.spy(
            Call(mockContext, client, client.socket, "123", audioManager)
        )
        assertEquals(call.callStateFlow.value, CallState.CONNECTING)
    }

    // NOOP tests, methods that should literally do nothing -- included for test coverage
    @Test
    fun `NOOP onClientReady test`() {
        assertDoesNotThrow {
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

            call = Mockito.spy(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onClientReady(JsonObject())
        }
    }

    @Test
    fun `NOOP onGatewayStateReceived test`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.socket = Mockito.spy(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )

            call = Mockito.spy(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onGatewayStateReceived("", "")
        }
    }

    @Test
    fun `NOOP onConnectionEstablished test`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.socket = Mockito.spy(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )

            call = Mockito.spy(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onConnectionEstablished()
        }
    }

    @Test
    fun `NOOP onErrorReceived test`() {
        assertDoesNotThrow {
            client = Mockito.spy(TelnyxClient(mockContext))
            client.socket = Mockito.spy(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )
            call = spyk(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            val jsonObject = JsonObject().apply {
                addProperty("id", "test-call-id")
                add("error", JsonObject().apply {
                    addProperty("message", "Your error message here")
                })
            }

            call.client.onErrorReceived(jsonObject, 0)
        }
    }
}

// Extension function for getOrAwaitValue for unit tests
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 10,
    timeUnit: TimeUnit = TimeUnit.SECONDS
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
