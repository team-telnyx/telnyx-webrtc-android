@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.*
import io.mockk.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V2PeerMediaTest {
    @Test fun `peer media records authoritative getters without inventing native track mute`() {
        val peer = mockk<PeerConnection>()
        val transceiver = mockk<RtpTransceiver>()
        val sender = mockk<RtpSender>()
        val receiver = mockk<RtpReceiver>()
        val local = mockk<AudioTrack>()
        val remote = mockk<AudioTrack>()
        every { peer.signalingState() } returns PeerConnection.SignalingState.HAVE_LOCAL_OFFER
        every { peer.iceGatheringState() } returns PeerConnection.IceGatheringState.GATHERING
        every { peer.iceConnectionState() } returns PeerConnection.IceConnectionState.CHECKING
        every { peer.connectionState() } returns PeerConnection.PeerConnectionState.CONNECTING
        every { transceiver.mediaType } returns MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        every { transceiver.sender } returns sender
        every { transceiver.receiver } returns receiver
        every { transceiver.direction } returns RtpTransceiver.RtpTransceiverDirection.SEND_RECV
        every { transceiver.currentDirection } returns RtpTransceiver.RtpTransceiverDirection.RECV_ONLY
        every { transceiver.mid } returns "0"
        every { sender.parameters } returns null
        every { receiver.parameters } returns null
        every { receiver.track() } returns remote
        every { local.enabled() } returns true
        every { remote.enabled() } returns false
        every { local.state() } returns MediaStreamTrack.State.LIVE
        every { remote.state() } returns MediaStreamTrack.State.ENDED
        val result = V2PeerMediaReader.snapshot(peer, local, transceiver)!!
        assertNull(result.sending)
        assertNull(result.input)
        assertEquals("have-local-offer", result.extra["peer"]!!.jsonObject["signaling_state"]!!.jsonPrimitive.content)
        assertEquals("sendrecv", result.extra["transceiver_direction"]!!.jsonPrimitive.content)
        assertEquals("recvonly", result.extra["transceiver_current_direction"]!!.jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), result.extra["remote_track"]!!.jsonObject["enabled"])
        assertEquals(JsonPrimitive("ended"), result.extra["remote_track"]!!.jsonObject["ready_state"])
        assertFalse(result.extra["remote_track"]!!.jsonObject.containsKey("muted"))
        assertFalse(result.extra["native_input_track"]!!.jsonObject.containsKey("muted"))
        verify(exactly = 0) { local.id() }
        verify(exactly = 0) { remote.id() }
        verify(exactly = 0) { peer.transceivers }
    }

    @Test fun `failed native getters do not crash host or prevent later snapshots`() {
        val peer = mockk<PeerConnection>()
        every { peer.signalingState() } throws UnsatisfiedLinkError("unavailable")
        every { peer.iceGatheringState() } throws IllegalStateException("disposed")
        every { peer.iceConnectionState() } throws IllegalStateException("disposed")
        every { peer.connectionState() } throws IllegalStateException("disposed")
        assertNull(V2PeerMediaReader.snapshot(peer, null))
        every { peer.signalingState() } returns PeerConnection.SignalingState.STABLE
        val recovered = V2PeerMediaReader.snapshot(peer, null)!!
        assertEquals("stable", recovered.extra["peer"]!!.jsonObject["signaling_state"]!!.jsonPrimitive.content)
        verify(exactly = 0) { peer.transceivers }
    }
}
