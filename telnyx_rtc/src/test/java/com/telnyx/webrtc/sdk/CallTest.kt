package com.telnyx.webrtc.sdk

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Client
import com.bugsnag.android.Configuration
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.ktor.client.features.websocket.*
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.mockito.Spy
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals


@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class CallTest: BaseTest() {

    @MockK
    private lateinit var mockContext: Context
    @MockK
    lateinit var client: TelnyxClient
    @MockK
    lateinit var socket: TxSocket
    @MockK
    lateinit var audioManager: AudioManager

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, true, true, true)
        socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )

        BuildConfig.IS_TESTING.set(true);

        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager
        every { audioManager.isMicrophoneMute = true } just Runs
        every { audioManager.isSpeakerphoneOn = true } just Runs
        every { audioManager.isSpeakerphoneOn} returns false
        every { audioManager.isMicrophoneMute} returns false

        client = Mockito.spy(
            TelnyxClient(
                mockContext
            )
        )
    }

    @Test
    fun `test call listen doesn't throw exception`() {
        assertDoesNotThrow { val newCall = Call(
            mockContext,
            client,
            socket,
            "123",
            audioManager
        ) }
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
        val newCall = Call(mockContext, client, socket, "123", audioManager)
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
}

//Extension function for getOrAwaitValue for unit tests
fun <T> LiveData<T>.getOrAwaitValue(
    time: Long = 10,
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