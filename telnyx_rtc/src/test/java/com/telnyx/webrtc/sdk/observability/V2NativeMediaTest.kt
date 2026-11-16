@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.RTCStats
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidateCandidateType
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidateNetworkType
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidatePayloadComponent
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidatePayloadSide
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidateProtocol
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidateRelayProtocol
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.IceCandidateTcpType
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** t025: structured ICE candidate extraction stores measured fields as sent, never fabricated. */
class V2NativeMediaTest {
    private fun row(id: String, type: String, members: Map<String, Any>): RTCStats = mockk<RTCStats>().also {
        every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
    }
    private fun report(vararg rows: RTCStats): RTCStatsReport =
        mockk<RTCStatsReport>().also { every { it.statsMap } returns rows.associateBy { it.id } }

    @Test fun `ice_candidate stores sdp fields as sent`() {
        val payload = V2NativeMedia.candidate("candidate:3412957284 1 udp 41885695 198.51.100.112 57816 typ relay", false, 1)
        assertEquals(IceCandidateCandidateType.RELAY, payload?.candidate_type)
        assertEquals(IceCandidateProtocol.UDP, payload?.protocol)
        assertEquals("3412957284", payload?.foundation)
        assertEquals(41885695.0, payload?.priority)
        assertEquals("198.51.100.112", payload?.address)
        assertEquals(57816.0, payload?.port)
        assertEquals(IceCandidatePayloadSide.LOCAL, payload?.side)
        assertEquals(IceCandidatePayloadComponent.RTP, payload?.component)
        assertEquals(1.0, payload?.ice_generation)
        assertNull(payload?.tcp_type)
        assertNull(payload?.relay_protocol)
        assertNull(payload?.network_type)
        assertNull(payload?.url)
    }

    @Test fun `ice_candidate keeps ipv6 and mdns addresses as sent`() {
        val ipv6 = V2NativeMedia.candidate("candidate:1 1 udp 2130706431 2001:db8::1 5000 typ host", false, 1)
        assertEquals("2001:db8::1", ipv6?.address)
        assertEquals(5000.0, ipv6?.port)
        val mdns = V2NativeMedia.candidate(
            "candidate:842163049 1 udp 1677729535 3f2fb35e-b31d-4ba8-bd3c-17b1e8e30df8.local 50768 typ host network-id 1", true, 1)
        assertEquals("3f2fb35e-b31d-4ba8-bd3c-17b1e8e30df8.local", mdns?.address)
        assertEquals(IceCandidatePayloadSide.REMOTE, mdns?.side)
    }

    @Test fun `ice_candidate maps tcp_type only for tcp candidates`() {
        val tcp = V2NativeMedia.candidate("candidate:1 1 tcp 1518280447 10.0.0.17 9 typ host tcptype active", false, 1)
        assertEquals(IceCandidateProtocol.TCP, tcp?.protocol)
        assertEquals(IceCandidateTcpType.ACTIVE, tcp?.tcp_type)
        assertEquals(9.0, tcp?.port)
        val passive = V2NativeMedia.candidate("candidate:2 1 tcp 2518280447 10.0.0.18 8443 typ srflx tcptype passive", false, 1)
        assertEquals(IceCandidateTcpType.PASSIVE, passive?.tcp_type)
        val so = V2NativeMedia.candidate("candidate:3 1 tcp 3518280447 10.0.0.19 443 typ relay tcptype so", false, 1)
        assertEquals(IceCandidateTcpType.SO, so?.tcp_type)
        val udpWithExtension = V2NativeMedia.candidate("candidate:4 1 udp 41885695 10.0.0.20 3478 typ host tcptype passive", false, 1)
        assertNull(udpWithExtension?.tcp_type)
    }

    @Test fun `ice_candidate keeps measured zero and rejects unmeasurable numbers`() {
        val zero = V2NativeMedia.candidate("candidate:1 1 tcp 0 10.0.0.5 0 typ host tcptype active", false, 1)
        assertEquals(0.0, zero?.priority)
        assertEquals(0.0, zero?.port)
        val negative = V2NativeMedia.candidate("candidate:1 1 udp -5 10.0.0.5 -1 typ host", false, 1)
        assertNull(negative?.priority)
        assertNull(negative?.port)
        val overflow = V2NativeMedia.candidate("candidate:1 1 udp 99999999999 10.0.0.5 99999 typ host", false, 1)
        assertNull(overflow?.priority)
        assertNull(overflow?.port)
        val nonNumeric = V2NativeMedia.candidate("candidate:1 1 udp x 10.0.0.5 y typ host", false, 1)
        assertNull(nonNumeric?.priority)
        assertNull(nonNumeric?.port)
    }

    @Test fun `ice_candidate tolerates extensions and rejects malformed lines`() {
        val extended = V2NativeMedia.candidate(
            "candidate:1 1 udp 2130706431 10.0.0.6 5000 typ srflx raddr 10.0.0.7 rport 5001 generation 0 ufrag abcd network-id 1 network-cost 10", false, 1)
        assertEquals(IceCandidateCandidateType.SRFLX, extended?.candidate_type)
        assertEquals("10.0.0.6", extended?.address)
        assertNull(extended?.tcp_type)
        assertNull(V2NativeMedia.candidate("candidate:1 1 udp 41885695 198.51.100.112", false, 1))
        assertNull(V2NativeMedia.candidate("candidate:1 1 udp 41885695 198.51.100.112 57816", false, 1))
        assertNull(V2NativeMedia.candidate("candidate:1 1 sctp 41885695 198.51.100.112 57816 typ host", false, 1))
        assertNull(V2NativeMedia.candidate("candidate:1 3 udp 41885695 198.51.100.112 57816 typ host", false, 1))
        assertNull(V2NativeMedia.candidate("candidate:1 1 udp 41885695 198.51.100.112 57816 typ bogus", false, 1))
        assertNull(V2NativeMedia.candidate("1 1 udp 41885695 198.51.100.112 57816 typ host", false, 1))
        assertNull(V2NativeMedia.candidate("", false, 1))
    }

    @Test fun `selected pair snapshots carry measured stats fields as sent`() {
        val media = V2NativeMedia.snapshot(report(
            row("transport", "transport", mapOf("selectedCandidatePairId" to "pair")),
            row("pair", "candidate-pair", mapOf("localCandidateId" to "l", "remoteCandidateId" to "r")),
            row("l", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "tls",
                "networkType" to "wifi", "foundation" to "3412957284", "priority" to 41885695,
                "address" to "198.51.100.112", "port" to 57816, "url" to "turn:user:secret@turn.telnyx.com:3478?transport=udp")),
            row("r", "remote-candidate", mapOf("candidateType" to "prflx", "protocol" to "tcp", "tcpType" to "passive",
                "foundation" to "7f2a1b", "priority" to 41885694, "ip" to "203.0.113.9", "port" to 9))))
        val local = media.local_candidate
        assertEquals(IceCandidateCandidateType.RELAY, local?.candidate_type)
        assertEquals(IceCandidateProtocol.UDP, local?.protocol)
        assertEquals(IceCandidateRelayProtocol.TLS, local?.relay_protocol)
        assertEquals(IceCandidateNetworkType.WIFI, local?.network_type)
        assertEquals("3412957284", local?.foundation)
        assertEquals(41885695.0, local?.priority)
        assertEquals("198.51.100.112", local?.address)
        assertEquals(57816.0, local?.port)
        assertEquals("turn:turn.telnyx.com:3478?transport=udp", local?.url)
        val remote = media.remote_candidate
        assertEquals(IceCandidateCandidateType.PRFLX, remote?.candidate_type)
        assertEquals(IceCandidateProtocol.TCP, remote?.protocol)
        assertEquals(IceCandidateTcpType.PASSIVE, remote?.tcp_type)
        assertEquals("7f2a1b", remote?.foundation)
        assertEquals(41885694.0, remote?.priority)
        assertEquals("203.0.113.9", remote?.address)
        assertEquals(9.0, remote?.port)
        assertNull(remote?.relay_protocol)
        assertNull(remote?.network_type)
        assertNull(remote?.url)
    }

    @Test fun `selected pair snapshots keep unavailable stats fields unknown`() {
        val media = V2NativeMedia.snapshot(report(
            row("pair", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded",
                "localCandidateId" to "l", "remoteCandidateId" to "r")),
            row("l", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp")),
            row("r", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "address" to "203.0.113.77", "port" to 70000, "priority" to -3))))
        val local = media.local_candidate
        assertEquals(IceCandidateCandidateType.HOST, local?.candidate_type)
        assertNull(local?.address)
        assertNull(local?.port)
        assertNull(local?.foundation)
        assertNull(local?.priority)
        assertNull(local?.tcp_type)
        assertNull(local?.url)
        val remote = media.remote_candidate
        assertNull(remote?.port)
        assertNull(remote?.priority)
        assertEquals("203.0.113.77", remote?.address)
    }

    @Test fun `candidate URLs never carry embedded credentials`() {
        val media = V2NativeMedia.snapshot(report(
            row("pair", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded",
                "localCandidateId" to "l", "remoteCandidateId" to "r")),
            row("l", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp",
                "url" to "turn:user:p%40ss@turn.telnyx.com:3478?transport=udp")),
            row("r", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp"))))
        val local = media.local_candidate
        assertEquals("turn:turn.telnyx.com:3478?transport=udp", local?.url)
        val exported = media.toString()
        assertFalse(exported.contains("p%40ss"))
        assertFalse(exported.contains("user:"))
        assertEquals("turn:turn1.telnyx.com:3478?transport=tcp",
            V2NativeMedia.stripUrlCredentials("turn:user:pass@turn1.telnyx.com:3478?transport=tcp"))
        assertEquals("turn:turn2.telnyx.com:3478",
            V2NativeMedia.stripUrlCredentials("turn:user@turn2.telnyx.com:3478"))
        assertEquals("turn://turn3.telnyx.com:3478?transport=udp",
            V2NativeMedia.stripUrlCredentials("turn://user:pass@turn3.telnyx.com:3478?transport=udp"))
        assertEquals("turns://turn4.telnyx.com:5349",
            V2NativeMedia.stripUrlCredentials("turns://user:pass@turn4.telnyx.com:5349"))
        assertEquals("TURN://turn5.telnyx.com:3478",
            V2NativeMedia.stripUrlCredentials("TURN://USER:PASS@turn5.telnyx.com:3478"))
        assertEquals("TURNS:turn6.telnyx.com:3478",
            V2NativeMedia.stripUrlCredentials("TURNS:USER:PASS@turn6.telnyx.com:3478"))
        assertEquals("turn:relay.example:3478",
            V2NativeMedia.stripUrlCredentials("turn:alice:secret-stuns:stillsecret@relay.example:3478"))
        assertEquals("TURN:relay.example:3478",
            V2NativeMedia.stripUrlCredentials("TURN:alice:secret-STUNS:x@relay.example:3478"))
        assertEquals("turns://relay.example:5349/path?x=@y",
            V2NativeMedia.stripUrlCredentials("turns://user:pass@relay.example:5349/path?x=@y"))
        assertEquals("turn:[2001:db8::1]:3478",
            V2NativeMedia.stripUrlCredentials("turn:alice:pass@[2001:db8::1]:3478"))
        assertEquals("alice:relay.example",
            V2NativeMedia.stripUrlCredentials("alice:secret@relay.example"))
        assertEquals("turn:host:3478?redirect=a:b@x",
            V2NativeMedia.stripUrlCredentials("turn:host:3478?redirect=a:b@x"))
        assertEquals("turn://host/p/a@t/h?x=1",
            V2NativeMedia.stripUrlCredentials("turn://host/p/a@t/h?x=1"))
        assertEquals("turn:ipv6-host.example:3478?x=@[2001:db8::1]",
            V2NativeMedia.stripUrlCredentials("turn:ipv6-host.example:3478?x=@[2001:db8::1]"))
        assertEquals("stun:no-credentials.example", V2NativeMedia.stripUrlCredentials("stun:no-credentials.example"))
        assertEquals("", V2NativeMedia.stripUrlCredentials(""))
    }

    @Test fun `stats numbers are whole valued or absent`() {
        val media = V2NativeMedia.snapshot(report(
            row("pair", "candidate-pair", mapOf("nominated" to true, "state" to "succeeded",
                "localCandidateId" to "l", "remoteCandidateId" to "r")),
            row("l", "local-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "address" to "198.51.100.112", "port" to 57816.5, "priority" to 41885695.5)),
            row("r", "remote-candidate", mapOf("candidateType" to "host", "protocol" to "udp",
                "address" to "203.0.113.9", "port" to 17314, "priority" to 41885694))))
        assertNull(media.local_candidate?.port)
        assertNull(media.local_candidate?.priority)
        assertEquals(17314.0, media.remote_candidate?.port)
        assertEquals(41885694.0, media.remote_candidate?.priority)
    }
}
