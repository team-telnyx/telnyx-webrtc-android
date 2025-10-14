package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.lib.MediaStreamTrack
import com.telnyx.webrtc.lib.RtpCapabilities
import com.telnyx.webrtc.lib.RtpParameters
import com.telnyx.webrtc.lib.RtpTransceiver
import com.telnyx.webrtc.sdk.model.AudioCodec

/**
 * Utility object for audio codec operations in WebRTC.
 * Provides functionality for codec preference management, codec matching, and conversions.
 */
internal object CodecUtils {

    /**
     * Gets the list of audio codecs that are currently available from the WebRTC audio transceiver.
     * This reflects the actual codecs that can be used for negotiation.
     *
     * @param audioTransceiver The audio RtpTransceiver to query
     * @return List of AudioCodec objects representing available codecs, or empty list if unavailable
     */
    fun getAvailableAudioCodecs(audioTransceiver: RtpTransceiver): List<AudioCodec> {
        return try {
            val receiverParameters = audioTransceiver.receiver.parameters
            receiverParameters.codecs.mapNotNull { rtpCodec ->
                try {
                    AudioCodec(
                        channels = rtpCodec.numChannels ?: 1,
                        clockRate = rtpCodec.clockRate ?: 0,
                        mimeType = "audio/${rtpCodec.name}",
                        sdpFmtpLine = rtpCodec.parameters?.get("fmtp")
                    )
                } catch (e: Exception) {
                    Logger.w(tag = "CodecQuery", message = "Error converting codec ${rtpCodec.name}: ${e.message}")
                    null
                }
            }
        } catch (e: Exception) {
            Logger.e(tag = "CodecQuery", message = "Error getting codecs: ${e.message}")
            emptyList()
        }
    }

    /**
     * Finds the audio transceiver from a list of transceivers.
     *
     * @param transceivers List of RTP transceivers to search
     * @return The audio transceiver, or null if not found
     */
    fun findAudioTransceiver(transceivers: List<RtpTransceiver>?): RtpTransceiver? {
        return transceivers?.find {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }
    }

    /**
     * Directly converts AudioCodec list to RtpCapabilities.CodecCapability list.
     * This bypasses the need to query receiver parameters, allowing codec preferences
     * to be set before SDP negotiation.
     *
     * This method creates CodecCapability instances directly from the user's preferred
     * codec list, which can then be passed to RtpTransceiver.setCodecPreferences() to
     * influence codec negotiation order.
     *
     * @param preferredCodecs List of preferred audio codecs in desired priority order
     * @return List of CodecCapability objects ready for setCodecPreferences()
     */
    fun convertAudioCodecsToCapabilities(
        preferredCodecs: List<AudioCodec>
    ): List<RtpCapabilities.CodecCapability> {
        return preferredCodecs.mapNotNull { audioCodec ->
            try {
                RtpCapabilities.CodecCapability().apply {
                    // Extract codec name from mimeType (e.g., "audio/opus" -> "opus")
                    name = audioCodec.mimeType.substringAfter("/")
                    mimeType = audioCodec.mimeType
                    kind = MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                    clockRate = audioCodec.clockRate
                    numChannels = audioCodec.channels
                    parameters = emptyMap() // Can be extended if needed for format-specific params
                    preferredPayloadType = 0 // WebRTC will assign appropriate payload type
                }
            } catch (e: Exception) {
                Logger.e(tag = "CodecPreferences", message = "Error converting ${audioCodec.mimeType}: ${e.message}")
                null
            }
        }
    }

    /**
     * Parses audio codecs from an SDP string by extracting rtpmap lines.
     * Example rtpmap line: a=rtpmap:111 opus/48000/2
     *
     * @param sdp The SDP string to parse
     * @return List of AudioCodec objects parsed from the SDP
     */
    fun parseAudioCodecsFromSdp(sdp: String): List<AudioCodec> {
        val codecs = mutableListOf<AudioCodec>()
        val lines = sdp.split("\r\n", "\n")
        var inAudioSection = false

        for (line in lines) {
            // Check if we're in the audio media section
            if (line.startsWith("m=audio")) {
                inAudioSection = true
                continue
            } else if (line.startsWith("m=")) {
                // Entered a different media section
                inAudioSection = false
                continue
            }

            // Only parse rtpmap lines within the audio section
            if (inAudioSection && line.startsWith("a=rtpmap:")) {
                try {
                    // Format: a=rtpmap:<payload_type> <codec_name>/<clock_rate>[/<channels>]
                    val parts = line.substring(9).split(" ", limit = 2)
                    if (parts.size == 2) {
                        val codecInfo = parts[1].split("/")
                        val codecName = codecInfo.getOrNull(0) ?: continue
                        val clockRate = codecInfo.getOrNull(1)?.toIntOrNull() ?: continue
                        val channels = codecInfo.getOrNull(2)?.toIntOrNull() ?: 1

                        codecs.add(
                            AudioCodec(
                                mimeType = "audio/$codecName",
                                clockRate = clockRate,
                                channels = channels,
                                sdpFmtpLine = null
                            )
                        )
                    }
                } catch (e: Exception) {
                    Logger.w(message = "Failed to parse rtpmap line: $line - ${e.message}")
                }
            }
        }

        return codecs
    }
}
