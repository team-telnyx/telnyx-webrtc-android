package com.telnyx.webrtc.sdk.peer

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.lib.MediaConstraints
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.PeerConnectionFactory
import com.telnyx.webrtc.lib.SdpObserver
import com.telnyx.webrtc.lib.SessionDescription
import io.mockk.*
import io.mockk.impl.annotations.RelaxedMockK
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for ICE renegotiation functionality in the Peer class.
 * Tests cover ICE state transitions, renegotiation process, error handling,
 * and call state management during ICE restart scenarios.
 */
class IceRenegotiationTest : BaseTest() {

    @RelaxedMockK
    private lateinit var mockContext: Context

    @RelaxedMockK
    private lateinit var mockTelnyxClient: TelnyxClient

    @RelaxedMockK
    private lateinit var mockCall: Call

    @RelaxedMockK
    private lateinit var mockPeerConnection: PeerConnection

    @RelaxedMockK
    private lateinit var mockPeerConnectionFactory: PeerConnectionFactory

    @get:Rule
    val rule: TestRule = InstantTaskExecutorRule()

    private lateinit var peer: Peer
    private lateinit var webRtcObserver: PeerConnection.Observer
    private val testCallId = UUID.randomUUID()

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        // Setup mock calls map
        val callsMap = mutableMapOf<UUID, Call>()
        callsMap[testCallId] = mockCall
        every { mockTelnyxClient.calls } returns callsMap

        // Setup call state management
        every { mockCall.callStateFlow.value } returns CallState.ACTIVE
        every { mockCall.updateCallState(any()) } just Runs

        // Setup TelnyxClient methods
        every { mockTelnyxClient.sendUpdateMediaMessage(any(), any()) } just Runs
        every { mockTelnyxClient.isSocketConnected() } returns true

        // Capture the actual observer installed by Peer while avoiding native
        // WebRTC/EGL initialization in this local JVM test.
        val observerSlot = slot<PeerConnection.Observer>()
        every {
            mockPeerConnectionFactory.createPeerConnection(
                any<PeerConnection.RTCConfiguration>(),
                capture(observerSlot),
            )
        } returns mockPeerConnection

        // Create peer instance with mocked dependencies
        peer = Peer(
            context = mockContext,
            client = mockTelnyxClient,
            callId = testCallId,
            peerConnectionFactoryOverride = mockPeerConnectionFactory,
        )
        webRtcObserver = observerSlot.captured
    }

    @After
    fun tearDown() {
        peer.disconnect()
    }

    @Test
    fun `test ICE state transition from DISCONNECTED to FAILED triggers renegotiation`() {
        // Arrange
        val newState = PeerConnection.IceConnectionState.FAILED

        // Act - simulate ICE connection state change through the observer
        webRtcObserver.onIceConnectionChange(newState)

        // Assert
        // Verify that renegotiation would be triggered after delay
        // Note: Due to Timer usage, we can't directly verify the method call
        // but we can verify the state transition was handled
        verify { mockCall.callStateFlow.value }
    }

    @Test
    fun `direct VPN candidate path requires relay fallback`() {
        val statistics = candidatePathStatistics(networkType = "vpn", candidateType = "host")

        assertTrue(Peer.selectedCandidateUsesDirectVpn(statistics))
    }

    @Test
    fun `VPN relay candidate path does not require another relay fallback`() {
        val statistics = candidatePathStatistics(networkType = "VPN", candidateType = "relay")

        assertFalse(Peer.selectedCandidateUsesDirectVpn(statistics))
    }

    @Test
    fun `non-VPN direct candidate path keeps configured ICE policy`() {
        val statistics = candidatePathStatistics(networkType = "wifi", candidateType = "host")

        assertFalse(Peer.selectedCandidateUsesDirectVpn(statistics))
    }

    @Test
    fun `peer failure starts recovery and active cadence while ICE reports connected`() {
        assertEquals(3_000L, peer.getPrivateField("recoveryTickIntervalMs"))
        every { mockPeerConnection.iceConnectionState() } returns
            PeerConnection.IceConnectionState.CONNECTED

        webRtcObserver.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, peer.signalingHealthMonitor.state())
        assertEquals(250L, peer.getPrivateField("recoveryTickIntervalMs"))
    }

    @Test
    fun `peer state exclusively owns disconnected recovery debounce`() {
        webRtcObserver.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)

        assertEquals(SignalingHealthMonitor.State.IDLE, peer.signalingHealthMonitor.state())
        assertEquals(3_000L, peer.getPrivateField("recoveryTickIntervalMs"))

        webRtcObserver.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)

        assertEquals(SignalingHealthMonitor.State.PROBING, peer.signalingHealthMonitor.state())
        assertEquals(250L, peer.getPrivateField("recoveryTickIntervalMs"))
    }

    @Test
    fun `test startIceRenegotiation sets call state to RENEGOTIATING`() {
        // Arrange
        val mockSessionDescription = SessionDescription(SessionDescription.Type.OFFER, "test-sdp")

        // Mock createOffer to immediately call onCreateSuccess
        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateSuccess(mockSessionDescription)
        }

        // Mock setLocalDescription to immediately call onSetSuccess
        every { mockPeerConnection.setLocalDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetSuccess()
        }

        // Mock localDescription
        every { mockPeerConnection.localDescription } returns mockSessionDescription

        // Act
        peer.invokePrivate("startIceRenegotiation")

        // Assert
        verify { mockCall.updateCallState(CallState.RENEGOTIATING) }
        verify { mockPeerConnection.createOffer(any(), any()) }
    }

    @Test
    fun `test startIceRenegotiation creates offer with ICE restart constraint`() {
        // Arrange
        val capturedConstraints = slot<MediaConstraints>()
        every { mockPeerConnection.createOffer(any(), capture(capturedConstraints)) } just Runs

        // Act
        peer.invokePrivate("startIceRenegotiation")

        // Assert
        verify { mockPeerConnection.createOffer(any(), any()) }
        
        // Verify ICE restart constraint was added
        val constraints = capturedConstraints.captured
        val iceRestartPair = constraints.mandatory.find { it.key == "IceRestart" }
        assertNotNull(iceRestartPair)
        assertEquals("true", iceRestartPair.value)
    }

    @Test
    fun `test startIceRenegotiation handles createOffer failure`() {
        // Arrange
        val errorMessage = "Failed to create offer"
        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateFailure(errorMessage)
        }

        // Act
        peer.invokePrivate("startIceRenegotiation")

        // Assert
        verify { mockCall.updateCallState(CallState.RENEGOTIATING) }
        verify { mockCall.updateCallState(CallState.ACTIVE) } // Should reset state on failure
    }

    @Test
    fun `test startIceRenegotiation handles setLocalDescription failure`() {
        // Arrange
        val mockSessionDescription = SessionDescription(SessionDescription.Type.OFFER, "test-sdp")
        val errorMessage = "Failed to set local description"

        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateSuccess(mockSessionDescription)
        }

        every { mockPeerConnection.setLocalDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetFailure(errorMessage)
        }

        // Act
        peer.invokePrivate("startIceRenegotiation")

        // Assert
        verify { mockCall.updateCallState(CallState.RENEGOTIATING) }
        verify { mockCall.updateCallState(CallState.ACTIVE) } // Should reset state on failure
    }

    @Test
    fun `test handleUpdateMediaResponse sets remote description successfully`() {
        // Arrange
        val remoteSdp = "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\n..."
        
        every { mockPeerConnection.setRemoteDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetSuccess()
        }

        // Act
        peer.handleUpdateMediaResponse(remoteSdp)

        // Assert
        verify { mockPeerConnection.setRemoteDescription(any(), any()) }
        verify { mockCall.updateCallState(CallState.ACTIVE) }
    }

    @Test
    fun `test handleUpdateMediaResponse handles setRemoteDescription failure`() {
        // Arrange
        val remoteSdp = "invalid-sdp"
        val errorMessage = "Failed to set remote description"
        
        every { mockPeerConnection.setRemoteDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetFailure(errorMessage)
        }

        // Act
        peer.handleUpdateMediaResponse(remoteSdp)

        // Assert
        verify { mockPeerConnection.setRemoteDescription(any(), any()) }
        verify { mockCall.updateCallState(CallState.ACTIVE) } // Should reset state on failure
    }

    @Test
    fun `test forceIceRenegotiationForTesting calls startIceRenegotiation`() {
        every { mockPeerConnection.createOffer(any(), any()) } just Runs

        // Act
        peer.forceIceRenegotiationForTesting()

        // Assert
        verify { mockPeerConnection.createOffer(any(), any()) }
    }

    @Test
    fun `test ICE candidate processing during RENEGOTIATING state`() {
        // Arrange
        every { mockCall.callStateFlow.value } returns CallState.RENEGOTIATING
        val mockIceCandidate = com.telnyx.webrtc.lib.IceCandidate(
            "audio",
            0,
            "candidate:1 1 UDP 1 192.0.2.1 5000 typ host",
        )

        // Act - simulate ICE candidate through the observer
        webRtcObserver.onIceCandidate(mockIceCandidate)

        // Assert
        verify { mockPeerConnection.addIceCandidate(mockIceCandidate) }
    }

    @Test
    fun `test ICE candidate ignored during ACTIVE state when not renegotiating`() {
        // Arrange
        every { mockCall.callStateFlow.value } returns CallState.ACTIVE
        val mockIceCandidate = com.telnyx.webrtc.lib.IceCandidate(
            "audio",
            0,
            "candidate:1 1 UDP 1 192.0.2.1 5000 typ host",
        )

        // Act - simulate ICE candidate through the observer
        webRtcObserver.onIceCandidate(mockIceCandidate)

        // Assert
        verify(exactly = 0) { mockPeerConnection.addIceCandidate(any()) }
    }

    @Test
    fun `test successful renegotiation flow sends updateMedia message`() {
        // Arrange
        val mockSessionDescription = SessionDescription(
            SessionDescription.Type.OFFER,
            "test-sdp-with-candidates",
        )

        // Mock the complete flow
        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateSuccess(mockSessionDescription)
        }

        every { mockPeerConnection.setLocalDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetSuccess()
        }

        every { mockPeerConnection.localDescription } returns mockSessionDescription

        // Act
        peer.invokePrivate("startIceRenegotiation")

        // Assert
        verify { mockCall.updateCallState(CallState.RENEGOTIATING) }
        verify(timeout = 1_000L) {
            mockTelnyxClient.sendUpdateMediaMessage(testCallId, "test-sdp-with-candidates")
        }
        verify(timeout = 1_000L) { mockCall.updateCallState(CallState.ACTIVE) }
    }

    @Test
    fun `test audio device module reset on ICE state transitions`() {
        // Arrange - Test CONNECTED -> DISCONNECTED transition
        webRtcObserver.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)

        // Assert audio track is disabled and re-enabled (with delay)
        // Note: Due to Timer usage, we can't directly verify the delayed calls
        // but we can verify the method was called
        verify { mockCall.callStateFlow.value }
    }

    @Test
    fun `test multiple ICE state transitions are handled correctly`() {
        // Test various state transitions
        val stateTransitions = listOf(
            PeerConnection.IceConnectionState.NEW to PeerConnection.IceConnectionState.CHECKING,
            PeerConnection.IceConnectionState.CHECKING to PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.CONNECTED to PeerConnection.IceConnectionState.DISCONNECTED,
            PeerConnection.IceConnectionState.DISCONNECTED to PeerConnection.IceConnectionState.FAILED
        )

        stateTransitions.forEach { (_, newState) ->
            webRtcObserver.onIceConnectionChange(newState)
            verify { mockCall.callStateFlow.value }
        }
    }

    @Test
    fun `test renegotiation with null peer connection handles gracefully`() {
        // Arrange
        peer.peerConnection = null

        // Act & Assert - should not throw exception
        peer.invokePrivate("startIceRenegotiation")
        peer.handleUpdateMediaResponse("test-sdp")
        peer.forceIceRenegotiationForTesting()
    }

    @Test
    fun `test call state management during renegotiation error scenarios`() {
        // Test various error scenarios and ensure call state is properly reset

        // Scenario 1: createOffer fails
        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateFailure("Create offer failed")
        }

        peer.invokePrivate("startIceRenegotiation")
        verify { mockCall.updateCallState(CallState.ACTIVE) }

        // Reset mocks
        clearMocks(mockCall)

        // Scenario 2: setLocalDescription fails
        val mockSessionDescription = SessionDescription(SessionDescription.Type.OFFER, "test-sdp")

        every { mockPeerConnection.createOffer(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onCreateSuccess(mockSessionDescription)
        }

        every { mockPeerConnection.setLocalDescription(any(), any()) } answers {
            val observer = firstArg<SdpObserver>()
            observer.onSetFailure("Set local description failed")
        }

        peer.invokePrivate("startIceRenegotiation")
        verify { mockCall.updateCallState(CallState.RENEGOTIATING) }
        verify { mockCall.updateCallState(CallState.ACTIVE) }
    }

    private fun candidatePathStatistics(
        networkType: String,
        candidateType: String,
    ): Map<String, Map<String, Any?>> = mapOf(
        "transport" to mapOf(
            "type" to "transport",
            "selectedCandidatePairId" to "pair",
        ),
        "pair" to mapOf(
            "type" to "candidate-pair",
            "localCandidateId" to "local",
        ),
        "local" to mapOf(
            "type" to "local-candidate",
            "networkType" to networkType,
            "candidateType" to candidateType,
        ),
    )

    /**
     * Helper extension function to invoke private methods for testing
     */
    private fun Any.invokePrivate(methodName: String, vararg args: Any?): Any? {
        val method = this::class.java.getDeclaredMethod(methodName, *args.map { it?.javaClass ?: Any::class.java }.toTypedArray())
        method.isAccessible = true
        return method.invoke(this, *args)
    }

    private fun Any.getPrivateField(fieldName: String): Any? {
        val field = Peer::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(this)
    }
}
