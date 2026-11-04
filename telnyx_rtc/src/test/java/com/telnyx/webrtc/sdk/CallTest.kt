package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.mockkObject
import io.mockk.verify
import android.net.ConnectivityManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.rules.TestRule
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class CallTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    lateinit var mockConnectivityManager: ConnectivityManager

    lateinit var client: TelnyxClient

    lateinit var socket: TxSocket

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
    fun `native INVITE binds V2 token even with quality reports disabled`() {
        val capture = mockk<com.telnyx.webrtc.sdk.observability.CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = capture
        client.providedTurn = Config.DEFAULT_TURN
        client.providedStun = Config.DEFAULT_STUN
        client.socket = socket
        every { client.playRingtone() } just Runs
        val id = UUID.randomUUID()
        val message = JsonObject().apply {
            addProperty("voice_sdk_id", "voice-id")
            add("params", JsonObject().apply {
                addProperty("callID", id.toString())
                addProperty("sdp", "v=0\r\n")
                addProperty("caller_id_name", "Caller")
                addProperty("caller_id_number", "100")
                addProperty("telnyx_session_id", UUID.randomUUID().toString())
                addProperty("telnyx_leg_id", UUID.randomUUID().toString())
            })
        }
        org.mockito.Mockito.mockConstruction(Peer::class.java).use { peers ->
            org.mockito.Mockito.mockConstruction(com.telnyx.webrtc.sdk.stats.WebRTCReporter::class.java).use { reporters ->
                client.onOfferReceived(message)
                assertEquals(1, peers.constructed().size)
                assertEquals(1, reporters.constructed().size)
                val call = client.getActiveCalls().values.single()
                verify { capture.started(call.v2CaptureToken, id.toString(), true, false, any(), any()) }
                org.mockito.Mockito.verify(peers.constructed().single()).v2CaptureToken = call.v2CaptureToken
            }
        }
    }

    @Test
    fun `V2 captures setup failure without a native peer and recovery setters use actual ingress`() {
        val capture = mockk<com.telnyx.webrtc.sdk.observability.CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = capture
        val failed = Call(mockContext, client, socket, "123", audioManager).apply {
            callId = UUID.randomUUID()
            beginV2Capture(inbound = true)
            setCallRecovering()
            setReconnectionTimeout()
        }
        verify { capture.state(failed.v2CaptureToken, CallState.RECONNECTING(com.telnyx.webrtc.sdk.model.CallNetworkChangeReason.NETWORK_SWITCH)) }
        verify { capture.state(failed.v2CaptureToken, CallState.ERROR) }
        verify { capture.peerClosing(failed.v2CaptureToken, null) }
    }

    @Test
    fun `V2 ingress retains distinct call tokens and signaling identity on reattach`() {
        val capture = mockk<com.telnyx.webrtc.sdk.observability.CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = capture
        val signaling = UUID.randomUUID()
        val first = Call(mockContext, client, socket, "123", audioManager).apply {
            callId = UUID.randomUUID()
            signalingCallId = signaling
            beginV2Capture(inbound = true, reattach = true)
            updateCallState(CallState.ACTIVE)
        }
        val second = Call(mockContext, client, socket, "123", audioManager).apply {
            callId = signaling
            beginV2Capture(inbound = false)
        }
        verify { capture.started(first.v2CaptureToken, signaling.toString(), true, true, any(), any()) }
        verify { capture.state(first.v2CaptureToken, CallState.ACTIVE) }
        verify { capture.started(second.v2CaptureToken, signaling.toString(), false, false, any(), any()) }
        assertFalse(first.v2CaptureToken === second.v2CaptureToken)
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
    fun `trickle ICE changes affect future calls without mutating an existing call`() {
        client.setUseTrickleIce(true)
        val existingCall = Call(mockContext, client, socket, "123", audioManager)

        client.setUseTrickleIce(false)
        val nextCall = Call(mockContext, client, socket, "123", audioManager)

        assertTrue(existingCall.useTrickleIce)
        assertFalse(nextCall.useTrickleIce)
    }

    @Test
    fun `call state changes notify peer recovery lifecycle`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        val mockPeer = mockk<Peer>(relaxed = true)
        newCall.peerConnection = mockPeer

        newCall.updateCallState(CallState.ACTIVE)

        verify { mockPeer.onCallStateChanged(CallState.ACTIVE) }
    }

    @Test
    fun `iceRestart starts recovery for active call with connected signaling`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        val mockPeer = mockk<Peer>(relaxed = true)
        newCall.callId = UUID.randomUUID()
        newCall.peerConnection = mockPeer
        newCall.updateCallState(CallState.ACTIVE)
        every { client.isSocketConnected() } returns true
        every { mockPeer.restartIce() } returns true

        assertTrue(newCall.iceRestart())

        verify(exactly = 1) { mockPeer.restartIce() }
    }

    @Test
    fun `iceRestart rejects a call that is not active`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        val mockPeer = mockk<Peer>(relaxed = true)
        newCall.peerConnection = mockPeer
        every { client.isSocketConnected() } returns true

        assertFalse(newCall.iceRestart())

        verify(exactly = 0) { mockPeer.restartIce() }
    }

    @Test
    fun `iceRestart rejects disconnected signaling`() {
        val newCall = Call(mockContext, client, socket, "123", audioManager)
        val mockPeer = mockk<Peer>(relaxed = true)
        newCall.callId = UUID.randomUUID()
        newCall.peerConnection = mockPeer
        newCall.updateCallState(CallState.ACTIVE)
        every { client.isSocketConnected() } returns false

        assertFalse(newCall.iceRestart())

        verify(exactly = 0) { mockPeer.restartIce() }
    }

    @Test
    fun `iceRestart forwards a stale call reference to its reattached replacement`() {
        val callId = UUID.randomUUID()
        val staleCall = Call(mockContext, client, socket, "123", audioManager)
        val replacementCall = Call(mockContext, client, socket, "123", audioManager)
        val stalePeer = mockk<Peer>(relaxed = true)
        val replacementPeer = mockk<Peer>(relaxed = true)
        staleCall.callId = callId
        staleCall.peerConnection = stalePeer
        staleCall.updateCallState(CallState.ACTIVE)
        replacementCall.callId = callId
        replacementCall.peerConnection = replacementPeer
        replacementCall.updateCallState(CallState.ACTIVE)
        every { client.getActiveCalls() } returns mapOf(callId to replacementCall)
        every { client.isSocketConnected() } returns true
        every { replacementPeer.restartIce() } returns true

        assertTrue(staleCall.iceRestart())

        verify(exactly = 0) { stalePeer.restartIce() }
        verify(exactly = 1) { replacementPeer.restartIce() }
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
        val callId = UUID.randomUUID()
        newCall.callId = callId
        newCall.endCall(callId)
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
        newCall.callId = UUID.randomUUID() // Initialize callId before using the call
        newCall.onLoudSpeakerPressed()
        assertEquals(newCall.getIsOnLoudSpeakerStatus().getOrAwaitValue(), true)
    }

    @Test
    fun `test new call is added to calls map - then assert that remove`() {
        socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        val newCall = spyk(Call(mockContext, client, socket, "123", audioManager))
        newCall.callId = UUID.randomUUID()
        client.addToCalls(newCall)
        assert(client.calls.containsValue(newCall))

        client.removeFromCalls(newCall.callId)
        assert(!client.calls.containsValue(newCall))
    }

    @Test
    fun `test dtmf pressed during call with value 2`() {
        client = spyk(TelnyxClient(mockContext))
        val testSocket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )
        client.socket = testSocket

        testSocket.connect(client)
        Thread.sleep(3000)


        call = spyk(
            Call(mockContext, client, testSocket, "123", audioManager)
        )
        call.dtmf(UUID.randomUUID(), "2")
        Thread.sleep(1000)
        verify(atLeast = 1) {
            testSocket.send(any<SendingMessageBody>())
        }
    }

    @Test
    fun `test new acceptCall from call where no SDP is contained`() {
        client = spyk(TelnyxClient(mockContext))
        client.socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        call = spyk(
            Call(mockContext, client, client.socket, "123", audioManager)
        )
        call.callId = UUID.randomUUID()
        client.addToCalls(call)
        call.acceptCall(call.callId, "00000")
        assertEquals(call.callStateFlow.value, CallState.ERROR)
    }

    @Test
    fun `Test call state returns callStateLiveData`() {
        client = spyk(TelnyxClient(mockContext))
        client.socket = spyk(
            TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938,
            )
        )

        call = spyk(
            Call(mockContext, client, client.socket, "123", audioManager)
        )
        assertEquals(call.callStateFlow.value, CallState.CONNECTING)
    }

    // NOOP tests, methods that should literally do nothing -- included for test coverage
    @Test
    fun `NOOP onClientReady test`() {
        assertDoesNotThrow {
            client = spyk(TelnyxClient(mockContext))
            client.socket = spyk(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )
            client.socket.connect(client)

            // Sleep to give time to connect
            Thread.sleep(3000)

            call = spyk(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onClientReady(JsonObject())
        }
    }

    @Test
    fun `NOOP onGatewayStateReceived test`() {
        assertDoesNotThrow {
            client = spyk(TelnyxClient(mockContext))
            client.socket = spyk(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )

            call = spyk(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onGatewayStateReceived("", "")
        }
    }

    @Test
    fun `NOOP onConnectionEstablished test`() {
        assertDoesNotThrow {
            client = spyk(TelnyxClient(mockContext))
            client.socket = spyk(
                TxSocket(
                    host_address = "rtc.telnyx.com",
                    port = 14938,
                )
            )

            call = spyk(
                Call(mockContext, client, client.socket, "123", audioManager)
            )
            call.client.onConnectionEstablished()
        }
    }

    @Test
    fun `NOOP onErrorReceived test`() {
        assertDoesNotThrow {
            client = spyk(TelnyxClient(mockContext))
            client.socket = spyk(
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
            if (value == null) return  // Skip null values — wait for a real emission
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
