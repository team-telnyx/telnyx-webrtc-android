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

    private const val DEFAULT_AUDIO_CHANNELS = 1

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
     * Converts RtpCapabilities.CodecCapability list to AudioCodec list.
     * Used to convert the capabilities returned from peerConnectionFactory.getRtpSenderCapabilities()
     * into the SDK's AudioCodec model.
     *
     * @param capabilities List of codec capabilities from WebRTC
     * @return List of AudioCodec objects
     */
    fun convertCapabilitiesToAudioCodecs(
        capabilities: List<RtpCapabilities.CodecCapability>
    ): List<AudioCodec> {
        return capabilities.mapNotNull { capability ->
            try {
                AudioCodec(
                    channels = capability.numChannels ?: DEFAULT_AUDIO_CHANNELS,
                    clockRate = capability.clockRate ?: 0,
                    mimeType = capability.mimeType ?: "audio/${capability.name}",
                    sdpFmtpLine = null
                )
            } catch (e: Exception) {
                Logger.e(tag = "CodecQuery", message = "Error converting capability ${capability.name}: ${e.message}")
                null
            }
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
}
