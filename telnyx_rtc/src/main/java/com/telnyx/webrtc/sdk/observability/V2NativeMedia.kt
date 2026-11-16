package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RTCStats
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.*
import kotlinx.serialization.json.*
import kotlin.math.floor

/** Extract structured ICE/media facts. Addresses are stored as sent in the beta (contract 1.8); credentials never are. */
internal object V2NativeMedia {
    const val CANDIDATE_CORE_FIELDS = 8
    private const val MAX_METADATA_LENGTH = 128
    private const val MAX_URL_LENGTH = 4096
    private const val MAX_PRIORITY = 2147483647.0
    private const val MAX_PORT = 65535.0

    /**
     * Removes "user[:password]@" from the URL's authority. The authority is anchored to the URL's actual
     * leading scheme (any `scheme:`/`scheme://` prefix, e.g. turn:, turns://, TURN:, stun:, https://), so
     * scheme-looking substrings inside userinfo (turn:alice:secret-stuns:stillsecret@host) can never be
     * mistaken for a new authority. The credential scan stops at path, query, fragment or whitespace
     * delimiters, and applies only to the first (leading) authority of this single server URL.
     */
    fun stripUrlCredentials(text: String): String {
        val match = leadingScheme.matchAt(text, 0) ?: return text
        val authorityStart = match.value.length
        var end = authorityStart
        var at = -1
        while (end < text.length) {
            val c = text[end]
            if (c == '/' || c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '?' || c == '#' || c == ';') break
            if (c == '@') at = end
            end += 1
        }
        if (at == -1) return text
        return text.removeRange(authorityStart, at + 1)
    }

    private val leadingScheme = Regex("^[A-Za-z][A-Za-z0-9+.\\-]*:(//)?")

    private fun safeUrl(url: String): String? =
        stripUrlCredentials(url.trim()).takeIf { it.isNotEmpty() }?.take(MAX_URL_LENGTH)

    /** Only canonical adapter types map; LOOPBACK and ADAPTER_TYPE_ANY have no contract value. */
    private fun networkType(adapter: PeerConnection.AdapterType?): IceCandidateNetworkType? = when (adapter) {
        PeerConnection.AdapterType.ETHERNET -> IceCandidateNetworkType.ETHERNET
        PeerConnection.AdapterType.WIFI -> IceCandidateNetworkType.WIFI
        PeerConnection.AdapterType.CELLULAR, PeerConnection.AdapterType.CELLULAR_2G,
        PeerConnection.AdapterType.CELLULAR_3G, PeerConnection.AdapterType.CELLULAR_4G,
        PeerConnection.AdapterType.CELLULAR_5G -> IceCandidateNetworkType.CELLULAR
        PeerConnection.AdapterType.VPN -> IceCandidateNetworkType.VPN
        PeerConnection.AdapterType.UNKNOWN -> IceCandidateNetworkType.UNKNOWN
        else -> null
    }

    private fun number(value: Any?, max: Double): Double? =
        (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 && it <= max && it == floor(it) }

    private fun sdpNumber(word: String?, max: Double): Double? =
        word?.toLongOrNull()?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 && it <= max }

    @Suppress("ReturnCount") // Reject malformed candidate components without manufacturing values.
    fun candidate(sdp: String, remote: Boolean, generation: Int, serverUrl: String? = null,
        adapterType: PeerConnection.AdapterType? = null): IceCandidatePayload? {
        val words = sdp.removePrefix("a=").trim().split(Regex("\\s+"))
        if (words.size < CANDIDATE_CORE_FIELDS || !words[0].startsWith("candidate:")) return null
        val typeIndex = words.indexOf("typ")
        val type = words.getOrNull(typeIndex + 1)?.uppercase()?.let { value ->
            IceCandidateCandidateType.values().firstOrNull { it.name == value }
        } ?: return null
        val protocol = IceCandidateProtocol.values().firstOrNull { it.name.equals(words[2], true) } ?: return null
        val component = when (words[1]) { "1" -> IceCandidatePayloadComponent.RTP; "2" -> IceCandidatePayloadComponent.RTCP; else -> return null }
        val tcpType = if (protocol == IceCandidateProtocol.TCP) {
            words.getOrNull(words.indexOf("tcptype") + 1)?.let { value ->
                IceCandidateTcpType.values().firstOrNull { it.name.equals(value, true) }
            }
        } else null
        return IceCandidatePayload(candidate_type = type, protocol = protocol,
            relay_protocol = null, network_type = networkType(adapterType),
            foundation = words[0].removePrefix("candidate:").takeIf { it.isNotEmpty() },
            priority = sdpNumber(words.getOrNull(3), MAX_PRIORITY),
            tcp_type = tcpType,
            address = words.getOrNull(4)?.takeIf { it.isNotEmpty() },
            port = sdpNumber(words.getOrNull(5), MAX_PORT),
            url = serverUrl?.let(::safeUrl),
            side = if (remote) IceCandidatePayloadSide.REMOTE else IceCandidatePayloadSide.LOCAL,
            ice_generation = generation.toDouble(), component = component)
    }

    fun snapshot(report: RTCStatsReport): CallMediaChangedPayload {
        val rows = report.statsMap
        val transport = rows.values.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] != null }
            ?: rows.values.firstOrNull { it.type == "transport" }
        fun audio(type: String) = rows.values.firstOrNull { it.type == type && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }
        val pair = (transport?.members?.get("selectedCandidatePairId") as? String)?.let { rows[it] }
            ?: rows.values.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true }
            ?: rows.values.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val dtls = CallMediaChangedPayloadDtlsState.values().firstOrNull { it.name.equals(transport?.members?.get("dtlsState") as? String, true) }
        val source = audio("media-source")?.members
        fun sourceNumber(name: String) = (source?.get(name) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        val outbound = audio("outbound-rtp")?.members
        val inbound = audio("inbound-rtp")?.members
        val extras = buildJsonObject {
            fun text(key: String, value: Any?) { (value as? String)?.let { put(key, it.take(MAX_METADATA_LENGTH)) } }
            fun number(key: String, value: Any?) { (value as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { put(key, it) } }
            text("ice_role", transport?.members?.get("iceRole"))
            text("dtls_role", transport?.members?.get("dtlsRole"))
            text("dtls_cipher", transport?.members?.get("dtlsCipher"))
            text("pair_state", pair?.members?.get("state"))
            (pair?.members?.get("nominated") as? Boolean)?.let { put("pair_nominated", it) }
            number("ssrc_in", inbound?.get("ssrc")); number("ssrc_out", outbound?.get("ssrc"))
            text("mid", inbound?.get("mid") ?: outbound?.get("mid"))
        }
        return CallMediaChangedPayload(emptyList(),
            target_bitrate_bps = (outbound?.get("targetBitrate") as? Number)?.toDouble()?.takeIf { it.isFinite() },
            sending = outbound?.get("active") as? Boolean,
            pair_changes = (transport?.members?.get("selectedCandidatePairChanges") as? Number)?.toDouble()?.takeIf { it.isFinite() },
            extra = extras.takeIf { it.isNotEmpty() }, codec_in = codec(rows, "inbound-rtp"), codec_out = codec(rows, "outbound-rtp"),
            local_candidate = selectedCandidate(rows, pair, "localCandidateId"), remote_candidate = selectedCandidate(rows, pair, "remoteCandidateId"),
            dtls_state = dtls, srtp_cipher = transport?.members?.get("srtpCipher") as? String,
            dtls_version = transport?.members?.get("tlsVersion") as? String,
            echo_return_loss_db = sourceNumber("echoReturnLoss"), echo_return_loss_enhancement_db = sourceNumber("echoReturnLossEnhancement"))
    }
    @Suppress("ReturnCount") // Required codec fields are independent availability gates.
    private fun codec(rows: Map<String, RTCStats>, type: String): Codec? {
        val ref = rows.values.firstOrNull { it.type == type && (it.members["kind"] == "audio" || it.members["mediaType"] == "audio") }?.members?.get("codecId") as? String ?: return null
        val values = rows[ref]?.members ?: return null
        fun num(name: String) = (values[name] as? Number)?.toDouble()?.takeIf { it.isFinite() }
        return Codec(values["mimeType"] as? String ?: return null, num("clockRate") ?: return null,
            num("channels") ?: 1.0, num("payloadType") ?: return null, values["sdpFmtpLine"] as? String)
    }
    @Suppress("ReturnCount") // Incomplete native candidates are unavailable, never defaulted.
    private fun selectedCandidate(rows: Map<String, RTCStats>, pair: RTCStats?, ref: String): IceCandidate? {
        val values = (pair?.members?.get(ref) as? String)?.let { rows[it]?.members } ?: return null
        val type = IceCandidateCandidateType.values().firstOrNull { it.name.equals(values["candidateType"] as? String, true) } ?: return null
        val protocol = IceCandidateProtocol.values().firstOrNull { it.name.equals(values["protocol"] as? String, true) } ?: return null
        return IceCandidate(candidate_type = type, protocol = protocol,
            relay_protocol = IceCandidateRelayProtocol.values().firstOrNull { it.name.equals(values["relayProtocol"] as? String, true) },
            network_type = IceCandidateNetworkType.values().firstOrNull { it.name.equals(values["networkType"] as? String, true) },
            foundation = (values["foundation"] as? String)?.takeIf { it.isNotEmpty() },
            priority = number(values["priority"], MAX_PRIORITY),
            tcp_type = if (protocol == IceCandidateProtocol.TCP) {
                IceCandidateTcpType.values().firstOrNull { it.name.equals(values["tcpType"] as? String, true) }
            } else null,
            address = (values["address"] as? String)?.takeIf { it.isNotEmpty() } ?: (values["ip"] as? String)?.takeIf { it.isNotEmpty() },
            port = number(values["port"], MAX_PORT),
            url = (values["url"] as? String)?.let(::safeUrl))
    }
    fun changes(next: CallMediaChangedPayload, previous: CallMediaChangedPayload?, pairChanged: Boolean): List<MediaChange> = buildList {
        fun changed(value: Any?, old: Any?) = value != null && value != old
        if (previous == null) add(MediaChange.INITIAL)
        if (changed(next.codec_in, previous?.codec_in) || changed(next.codec_out, previous?.codec_out) || changed(next.target_bitrate_bps, previous?.target_bitrate_bps)) add(MediaChange.CODEC)
        if (pairChanged || changed(next.local_candidate, previous?.local_candidate) || changed(next.remote_candidate, previous?.remote_candidate)) add(MediaChange.CANDIDATE_PAIR)
        if (listOf("dtls_role", "dtls_cipher").any { changed(next.extra?.get(it), previous?.extra?.get(it)) } ||
            listOf(next.dtls_state to previous?.dtls_state, next.srtp_cipher to previous?.srtp_cipher, next.dtls_version to previous?.dtls_version).any { changed(it.first, it.second) }) add(MediaChange.DTLS_STATE)
        if (changed(next.sending, previous?.sending)) add(MediaChange.SENDING)
        if (changed(next.echo_return_loss_db, previous?.echo_return_loss_db) || changed(next.echo_return_loss_enhancement_db, previous?.echo_return_loss_enhancement_db)) add(MediaChange.ECHO)
    }

    fun extraChanges(next: CallMediaChangedPayload, previous: CallMediaChangedPayload?): List<String> =
        mapOf("peer_state" to listOf("pair_state", "pair_nominated"),
            "rtp_parameters" to listOf("ssrc_in", "ssrc_out", "mid")).filter { (_, keys) ->
            keys.any { next.extra?.get(it) != null && next.extra?.get(it) != previous?.extra?.get(it) }
        }.keys.toList()

}
