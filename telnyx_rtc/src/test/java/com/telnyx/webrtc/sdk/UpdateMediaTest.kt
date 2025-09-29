package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import com.telnyx.webrtc.sdk.verto.receive.UpdateMediaResponse
import com.telnyx.webrtc.sdk.verto.send.ModifyParams
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import io.mockk.*
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.rules.TestRule
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for updateMedia message handling in TelnyxClient.
 * Tests cover sending updateMedia messages and processing updateMedia responses
 * for ICE renegotiation functionality.
 */
@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class UpdateMediaTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    @RelaxedMockK
    private lateinit var mockTxSocket: TxSocket

    @RelaxedMockK
    private lateinit var mockCall: Call

    @RelaxedMockK
    private lateinit var mockPeer: Peer

    @RelaxedMockK
    private lateinit var mockAudioManager: AudioManager

    @get:Rule
    val rule: TestRule = InstantTaskExecutorRule()

    private lateinit var telnyxClient: TelnyxClient
    private val testCallId = UUID.randomUUID()
    private val testSessid = "test-session-id"

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)

        // Create TelnyxClient instance
        telnyxClient = spyk(TelnyxClient(mockContext))

        // Setup socket mock
        every { telnyxClient.socket } returns mockTxSocket
        every { mockTxSocket.send(any<SendingMessageBody>()) } just Awaits

        // Setup session ID
        telnyxClient.sessid = testSessid

        // Setup calls map
        val callsMap = mutableMapOf<UUID, Call>()
        callsMap[testCallId] = mockCall
        every { telnyxClient.calls } returns callsMap

        // Setup call and peer mocks
        every { mockCall.peerConnection } returns mockPeer
        every { mockCall.callStateFlow.value } returns CallState.ACTIVE
        every { mockCall.updateCallState(any()) } just Runs
        every { mockPeer.handleUpdateMediaResponse(any()) } just Runs
    }

    @Test
    fun `test sendUpdateMediaMessage creates correct modify message`() {
        // Arrange
        val testSdp = "v=0\r\no=- 123456789 2 IN IP4 127.0.0.1\r\n..."
        val capturedMessage = slot<SendingMessageBody>()

        // Act
        telnyxClient.sendUpdateMediaMessage(testCallId, testSdp)

        // Assert
        verify { mockTxSocket.send(capture(capturedMessage)) }

        val message = capturedMessage.captured
        assertNotNull(message.id)
        assertEquals("telnyx_rtc.modify", message.method)

        val params = message.params as ModifyParams
        assertEquals(testSessid, params.sessid)
        assertEquals("updateMedia", params.action)
        assertEquals(testSdp, params.sdp)
        assertEquals(testCallId, params.dialogParams.callId)
    }

    @Test
    fun `test sendUpdateMediaMessage with empty SDP`() {
        // Arrange
        val emptySdp = ""
        val capturedMessage = slot<SendingMessageBody>()

        // Act
        telnyxClient.sendUpdateMediaMessage(testCallId, emptySdp)

        // Assert
        verify { mockTxSocket.send(capture(capturedMessage)) }

        val message = capturedMessage.captured
        val params = message.params as ModifyParams
        assertEquals(emptySdp, params.sdp)
    }

    @Test
    fun `test sendUpdateMediaMessage with complex SDP containing ICE candidates`() {
        // Arrange
        val complexSdp = """
            v=0
            o=- 123456789 2 IN IP4 127.0.0.1
            s=-
            t=0 0
            a=group:BUNDLE 0
            a=msid-semantic: WMS
            m=audio 9 UDP/TLS/RTP/SAVPF 111 103 104 9 0 8 106 105 13 110 112 113 126
            c=IN IP4 0.0.0.0
            a=rtcp:9 IN IP4 0.0.0.0
            a=ice-ufrag:4ZcD
            a=ice-pwd:2/1muCWoOi3uLifh0tADgBmjR
            a=ice-options:trickle
            a=fingerprint:sha-256 75:74:5A:A6:A4:E5:52:F4:A7:67:4C:01:C7:EE:91:3F:21:3D:A2:E3:53:7B:6F:30:86:F2:30:FF:A6:22:D2:04
            a=setup:actpass
            a=mid:0
            a=extmap:1 urn:ietf:params:rtp-hdrext:ssrc-audio-level
            a=candidate:842163049 1 udp 1677729535 192.168.1.100 54400 typ srflx raddr 192.168.1.100 rport 54400 generation 0 ufrag 4ZcD network-cost 999
        """.trimIndent()
        val capturedMessage = slot<SendingMessageBody>()

        // Act
        telnyxClient.sendUpdateMediaMessage(testCallId, complexSdp)

        // Assert
        verify { mockTxSocket.send(capture(capturedMessage)) }

        val message = capturedMessage.captured
        val params = message.params as ModifyParams
        assertEquals(complexSdp, params.sdp)
    }

    @Test
    fun `test onModifyReceived processes updateMedia response successfully`() {
        // Arrange
        val testRemoteSdp = "v=0\r\no=- 987654321 2 IN IP4 10.0.0.1\r\n..."
        val updateMediaResponse = UpdateMediaResponse(
            action = "updateMedia",
            callId = testCallId,
            sdp = testRemoteSdp
        )

        val jsonResponse = JsonObject().apply {
            add("result", Gson().toJsonTree(updateMediaResponse))
        }

        // Act
        telnyxClient.onModifyReceived(jsonResponse)

        // Assert
        verify { mockPeer.handleUpdateMediaResponse(testRemoteSdp) }
    }

    @Test
    fun `test onModifyReceived handles missing call gracefully`() {
        // Arrange
        val nonExistentCallId = UUID.randomUUID()
        val updateMediaResponse = UpdateMediaResponse(
            action = "updateMedia",
            callId = nonExistentCallId,
            sdp = "test-sdp"
        )

        val jsonResponse = JsonObject().apply {
            add("result", Gson().toJsonTree(updateMediaResponse))
        }

        // Act & Assert - should not throw exception
        telnyxClient.onModifyReceived(jsonResponse)

        // Verify no peer method was called since call doesn't exist
        verify(exactly = 0) { mockPeer.handleUpdateMediaResponse(any()) }
    }

    @Test
    fun `test onModifyReceived handles missing peer gracefully`() {
        // Arrange
        every { mockCall.peerConnection } returns null

        val updateMediaResponse = UpdateMediaResponse(
            action = "updateMedia",
            callId = testCallId,
            sdp = "test-sdp"
        )

        val jsonResponse = JsonObject().apply {
            add("result", Gson().toJsonTree(updateMediaResponse))
        }

        // Act & Assert - should not throw exception
        telnyxClient.onModifyReceived(jsonResponse)

        // Verify no peer method was called since peer is null
        verify(exactly = 0) { mockPeer.handleUpdateMediaResponse(any()) }
    }

    @Test
    fun `test onModifyReceived handles malformed JSON gracefully`() {
        // Arrange
        val malformedJson = JsonObject().apply {
            addProperty("invalid", "structure")
        }

        // Act & Assert - should not throw exception
        telnyxClient.onModifyReceived(malformedJson)

        // Verify no peer method was called due to malformed JSON
        verify(exactly = 0) { mockPeer.handleUpdateMediaResponse(any()) }
    }

    @Test
    fun `test onModifyReceived handles missing result object gracefully`() {
        // Arrange
        val jsonWithoutResult = JsonObject().apply {
            addProperty("method", "telnyx_rtc.modify")
            addProperty("id", "test-id")
        }

        // Act & Assert - should not throw exception
        telnyxClient.onModifyReceived(jsonWithoutResult)

        // Verify no peer method was called due to missing result
        verify(exactly = 0) { mockPeer.handleUpdateMediaResponse(any()) }
    }

    @Test
    fun `test onModifyReceived processes multiple updateMedia responses`() {
        // Arrange
        val testCallId2 = UUID.randomUUID()
        val mockCall2 = mockk<Call>(relaxed = true)
        val mockPeer2 = mockk<Peer>(relaxed = true)

        // Add second call to the map
        telnyxClient.calls[testCallId2] = mockCall2
        every { mockCall2.peerConnection } returns mockPeer2
        every { mockPeer2.handleUpdateMediaResponse(any()) } just Runs

        val responses = listOf(
            UpdateMediaResponse("updateMedia", testCallId, "sdp1"),
            UpdateMediaResponse("updateMedia", testCallId2, "sdp2")
        )

        // Act
        responses.forEach { response ->
            val jsonResponse = JsonObject().apply {
                add("result", Gson().toJsonTree(response))
            }
            telnyxClient.onModifyReceived(jsonResponse)
        }

        // Assert
        verify { mockPeer.handleUpdateMediaResponse("sdp1") }
        verify { mockPeer2.handleUpdateMediaResponse("sdp2") }
    }

    @Test
    fun `test updateMedia response with different action types`() {
        // Arrange - test with non-updateMedia action
        val otherActionResponse = JsonObject().apply {
            add("result", JsonObject().apply {
                addProperty("action", "otherAction")
                addProperty("callID", testCallId.toString())
                addProperty("sdp", "test-sdp")
            })
        }

        // Act & Assert - should handle gracefully even with different action
        telnyxClient.onModifyReceived(otherActionResponse)

        // The current implementation doesn't filter by action type,
        // so it should still process the response
        verify { mockPeer.handleUpdateMediaResponse("test-sdp") }
    }

    @Test
    fun `test sendUpdateMediaMessage generates unique message IDs`() {
        // Arrange
        val testSdp = "test-sdp"
        val capturedMessages = mutableListOf<SendingMessageBody>()

        every { mockTxSocket.send(capture(capturedMessages)) } just Awaits

        // Act - send multiple messages
        repeat(3) {
            telnyxClient.sendUpdateMediaMessage(testCallId, testSdp)
        }

        // Assert - all message IDs should be unique
        val messageIds = capturedMessages.map { it.id }
        assertEquals(3, messageIds.size)
        assertEquals(3, messageIds.toSet().size) // All IDs should be unique
    }

    @Test
    fun `test updateMedia response parsing with special characters in SDP`() {
        // Arrange
        val sdpWithSpecialChars = "v=0\r\no=- 123 2 IN IP4 127.0.0.1\r\ns=Test \"Session\" with 'quotes'\r\n"
        val updateMediaResponse = UpdateMediaResponse(
            action = "updateMedia",
            callId = testCallId,
            sdp = sdpWithSpecialChars
        )

        val jsonResponse = JsonObject().apply {
            add("result", Gson().toJsonTree(updateMediaResponse))
        }

        // Act
        telnyxClient.onModifyReceived(jsonResponse)

        // Assert
        verify { mockPeer.handleUpdateMediaResponse(sdpWithSpecialChars) }
    }
}