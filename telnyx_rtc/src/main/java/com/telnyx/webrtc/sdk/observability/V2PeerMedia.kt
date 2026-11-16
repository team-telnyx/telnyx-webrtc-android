package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.AudioTrack
import com.telnyx.webrtc.lib.MediaStreamTrack
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RtpParameters
import com.telnyx.webrtc.lib.RtpTransceiver
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.InputDevice
import kotlinx.serialization.json.*
import java.util.Locale

/** Getter-only snapshot. Caller must serialize these getters against native peer disposal. */
internal data class V2PeerMedia(val sending: Boolean?, val input: InputDevice?, val extra: JsonObject)

internal object V2PeerMediaReader {
    fun snapshot(peer: PeerConnection, localTrack: AudioTrack?, transceiver: RtpTransceiver? = null): V2PeerMedia? {
        // Never enumerate peer.transceivers: that getter disposes cached Java wrappers.
        // The SDK supplies its current audio transceiver inside its existing lookup/iteration.
        val sender = attempt { transceiver?.sender }
        val receiver = attempt { transceiver?.receiver }
        val send = attempt { sender?.parameters }
        val receive = attempt { receiver?.parameters }
        val sending = attempt { send?.encodings?.takeIf { it.isNotEmpty() }?.any { it.active } }
        val extra = buildJsonObject {
            val states = buildJsonObject {
                attempt { peer.signalingState()?.wire() }?.let { put("signaling_state", it) }
                attempt { peer.iceGatheringState()?.wire() }?.let { put("ice_gathering_state", it) }
                attempt { peer.iceConnectionState()?.wire() }?.let { put("ice_connection_state", it) }
                attempt { peer.connectionState()?.wire() }?.let { put("connection_state", it) }
            }
            if (states.isNotEmpty()) put("peer", states)
            attempt { transceiver?.direction?.name?.lowercase(Locale.ROOT)?.replace("_", "") }
                ?.let { put("transceiver_direction", it) }
            attempt { transceiver?.currentDirection?.name?.lowercase(Locale.ROOT)?.replace("_", "") }
                ?.let { put("transceiver_current_direction", it) }
            attempt { transceiver?.mid?.takeIf { MID.matches(it) } }?.let { put("mid", it) }
            parameters(send)?.let { put("send_parameters", it) }
            parameters(receive)?.let { put("receive_parameters", it) }
            track(attempt { receiver?.track() })?.let { put("remote_track", it) }
            // Canonical InputDevice requires `muted`; Android track has no such getter.
            // Keep observed enabled/state separate until an authoritative mute source is available.
            track(localTrack)?.let { observed ->
                put("native_input_track", JsonObject(observed + ("label" to JsonPrimitive("microphone"))))
            }
        }
        return if (extra.isEmpty() && sending == null) null else V2PeerMedia(sending, null, extra)
    }

    private fun track(track: MediaStreamTrack?): JsonObject? {
        if (track == null) return null
        return buildJsonObject {
            attempt { track.enabled() }?.let { put("enabled", it) }
            attempt { track.state()?.wire() }?.let { put("ready_state", it) }
        }.takeIf { it.isNotEmpty() }
    }

    private fun parameters(parameters: RtpParameters?): JsonObject? {
        if (parameters == null) return null
        return attempt {
            buildJsonObject {
                val codecs = parameters.codecs.orEmpty().mapNotNull { codec ->
                    val name = codec.name?.takeIf { TOKEN.matches(it) } ?: return@mapNotNull null
                    buildJsonObject {
                        put("mime_type", "audio/$name")
                        codec.clockRate?.let { put("clock_rate", it) }
                        codec.numChannels?.let { put("channels", it) }
                        put("payload_type", codec.payloadType)
                        val fmtp = codec.parameters.orEmpty().filter { (key, value) ->
                            key in FMTP_KEYS && value.length <= MAX_VALUE_LENGTH && NUMERIC_VALUE.matches(value)
                        }.toSortedMap().entries.joinToString(";") { (key, value) -> "$key=$value" }
                        if (fmtp.isNotEmpty()) put("sdp_fmtp_line", fmtp)
                    }
                }
                if (codecs.isNotEmpty()) put("codecs", JsonArray(codecs))
                val extensions = parameters.headerExtensions.orEmpty().mapNotNull { extension ->
                    extension.uri?.takeIf { it in HEADER_URIS }?.let(::JsonPrimitive)
                }
                if (extensions.isNotEmpty()) put("header_extensions", JsonArray(extensions))
                parameters.rtcp?.let { put("rtcp_reduced_size", it.reducedSize) }
                val encodings = parameters.encodings.orEmpty().map { encoding ->
                    buildJsonObject {
                        put("active", encoding.active)
                        encoding.maxBitrateBps?.let { put("max_bitrate_bps", it) }
                        encoding.rid?.takeIf { MID.matches(it) }?.let { put("rid", it) }
                        // Numeric native priority fields do not use browser priority enum semantics.
                        // Native adaptiveAudioPacketTime is not a measured ptime or DTX state.
                    }
                }
                if (encodings.isNotEmpty()) put("encodings", JsonArray(encodings))
                parameters.degradationPreference?.wire()?.let { put("degradation_preference", it) }
            }.takeIf { it.isNotEmpty() }
        }
    }

    private fun Enum<*>.wire() = name.lowercase(Locale.ROOT).replace('_', '-')

    private fun <T> attempt(read: () -> T): T? = try {
        read()
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    }

    private const val MAX_VALUE_LENGTH = 32
    private val TOKEN = Regex("[A-Za-z0-9._-]{1,64}")
    private val MID = Regex("[A-Za-z0-9_-]{1,64}")
    private val NUMERIC_VALUE = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val FMTP_KEYS = setOf("minptime", "useinbandfec", "stereo", "sprop-stereo", "maxplaybackrate",
        "maxaveragebitrate", "usedtx", "cbr", "ptime", "maxptime")
    private val HEADER_URIS = setOf(
        "urn:ietf:params:rtp-hdrext:ssrc-audio-level",
        "urn:ietf:params:rtp-hdrext:sdes:mid",
        "urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id",
        "urn:ietf:params:rtp-hdrext:sdes:repaired-rtp-stream-id",
        "http://www.webrtc.org/experiments/rtp-hdrext/abs-send-time",
        "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"
    )
}
