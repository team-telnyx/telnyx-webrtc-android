package com.telnyx.webrtc.sdk.peer

import android.content.Context
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.stats.DebugDataCollector
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.PeerConnectionFactory
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.just
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID
import com.telnyx.webrtc.sdk.testhelpers.BaseTest

/**
 * Regression tests for VSUP-324 (Beside native crash report):
 *
 * 1. Double dispose — two overlapping teardown callers (e.g. endCall racing
 *    an onByeReceived on the socket thread) must not run close()/dispose()
 *    twice on the same PeerConnection.
 * 2. Use-after-free — get*Description() must never touch a PeerConnection
 *    whose native object was freed by a concurrent disconnect(); after
 *    (or during) teardown they return null.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLifecycleCrashTest : BaseTest() {

    @RelaxedMockK
    private lateinit var mockContext: Context

    @RelaxedMockK
    private lateinit var mockTelnyxClient: TelnyxClient

    @RelaxedMockK
    private lateinit var mockCall: Call

    @RelaxedMockK
    private lateinit var mockPeerConnection: PeerConnection

    @RelaxedMockK
    private lateinit var mockSocket: TxSocket

    @RelaxedMockK
    private lateinit var mockPeerConnectionFactory: PeerConnectionFactory

    @RelaxedMockK
    private lateinit var mockDebugDataCollector: DebugDataCollector

    private lateinit var peer: Peer
    private val testCallId = UUID.randomUUID()

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(StandardTestDispatcher())

        val callsMap = mutableMapOf<UUID, Call>()
        callsMap[testCallId] = mockCall
        every { mockTelnyxClient.calls } returns callsMap
        every { mockCall.callStateFlow.value } returns CallState.ACTIVE
        every { mockCall.updateCallState(any()) } just Runs
        every { mockTelnyxClient.sendUpdateMediaMessage(any(), any(), any()) } just Runs
        every { mockTelnyxClient.isSocketConnected() } returns true
        every { mockTelnyxClient.getUseTrickleIce() } returns false
        every { mockTelnyxClient.debugDataCollector } returns mockDebugDataCollector
        every { mockTelnyxClient.sessid } returns "session-id"
        every { mockTelnyxClient.socket } returns mockSocket

        val observerSlot = io.mockk.slot<PeerConnection.Observer>()
        every {
            mockPeerConnectionFactory.createPeerConnection(
                any<PeerConnection.RTCConfiguration>(),
                capture(observerSlot),
            )
        } returns mockPeerConnection

        peer = Peer(
            context = mockContext,
            client = mockTelnyxClient,
            callId = testCallId,
            peerConnectionFactoryOverride = mockPeerConnectionFactory,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // Ten test class never installs a real connection; this mirrors the
        // idempotence guarantee under test.
        peer.release()
    }

    @Test
    fun `double disconnect runs dispose exactly once`() {
        peer.disconnect()
        peer.disconnect()

        verify(exactly = 1) { mockPeerConnection.close() }
        verify(exactly = 1) { mockPeerConnection.dispose() }
    }

    @Test
    fun `release after disconnect does not dispose twice`() {
        peer.disconnect()
        peer.release()

        verify(exactly = 1) { mockPeerConnection.close() }
        verify(exactly = 1) { mockPeerConnection.dispose() }
    }

    @Test
    fun `getLocalDescription returns null after disconnect`() {
        peer.disconnect()
        assertNull(peer.getLocalDescription())
    }

    @Test
    fun `getRemoteDescription returns null after disconnect`() {
        peer.disconnect()
        assertNull(peer.getRemoteDescription())
    }

    @Test
    fun `disconnect clears peerConnection reference before closing`() {
        // Instrument close() to observe the Peer's reference state while it runs:
        // after the fix the reference is cleared BEFORE close() is invoked.
        var peerConnectionRefDuringClose: Any? = null
        every { mockPeerConnection.close() } answers {
            peerConnectionRefDuringClose = peer.peerConnection
            Unit
        }

        peer.disconnect()

        assertNull("Reference must be cleared before close()/dispose() run", peerConnectionRefDuringClose)
        assertEquals("isDisposed must be set when teardown claims the peer", true, peer.isDisposed.get())
    }
}
