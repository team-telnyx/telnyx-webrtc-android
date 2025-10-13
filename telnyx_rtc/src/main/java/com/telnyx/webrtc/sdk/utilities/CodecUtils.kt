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
     * Builds a reordered codec list based on user preferences.
     *
     * This method retrieves the actual negotiated codecs from the audio transceiver's receiver
     * parameters and reorders them according to the provided preferences. Codecs matching the
     * preferences are placed first in the order specified, followed by remaining codecs.
     *
     * @param audioTransceiver The audio RtpTransceiver to query for available codecs
     * @param preferredCodecs List of preferred audio codecs in desired priority order
     * @return List of reordered codec capabilities, or null if unable to build the list
     */
    @Suppress("ReturnCount") // Multiple validation checks require early returns
    fun buildReorderedCodecList(
        audioTransceiver: RtpTransceiver,
        preferredCodecs: List<AudioCodec>
    ): List<RtpCapabilities.CodecCapability>? {
        // Get the actual codecs from the receiver's parameters
        val receiverParameters = try {
            audioTransceiver.receiver.parameters
        } catch (e: Exception) {
            Logger.e(tag = "CodecPreferences", message = "Error getting receiver parameters: ${e.message}")
            return null
        }

        val availableCodecs = receiverParameters.codecs
        if (availableCodecs.isEmpty()) {
            Logger.w(tag = "CodecPreferences", message = "No codecs available from receiver parameters")
            return null
        }

        Logger.d(tag = "CodecPreferences", message = "Available codecs from receiver: ${availableCodecs.map { "${it.name}/${it.clockRate}" }}")
        Logger.d(tag = "CodecPreferences", message = "Preferred codecs: ${preferredCodecs.map { it.mimeType }}")

        // Convert RtpParameters.Codec to RtpCapabilities.CodecCapability and reorder based on preferences
        val reorderedCapabilities = mutableListOf<RtpCapabilities.CodecCapability>()
        val usedCodecs = mutableSetOf<RtpParameters.Codec>()

        // First, add codecs that match user preferences in the specified order
        for (preferredCodec in preferredCodecs) {
            val matchingCodec = findMatchingRtpCodec(preferredCodec, availableCodecs, usedCodecs)
            if (matchingCodec != null) {
                val capability = convertRtpCodecToCapability(matchingCodec)
                if (capability != null) {
                    reorderedCapabilities.add(capability)
                    usedCodecs.add(matchingCodec)
                    Logger.d(tag = "CodecPreferences", message = "Added preferred codec: ${matchingCodec.name}/${matchingCodec.clockRate}")
                }
            } else {
                Logger.w(tag = "CodecPreferences", message = "Preferred codec ${preferredCodec.mimeType} not found in available codecs")
            }
        }

        // Then add remaining codecs that weren't in preferences
        for (codec in availableCodecs) {
            if (codec !in usedCodecs) {
                val capability = convertRtpCodecToCapability(codec)
                if (capability != null) {
                    reorderedCapabilities.add(capability)
                    Logger.d(tag = "CodecPreferences", message = "Added remaining codec: ${codec.name}/${codec.clockRate}")
                }
            }
        }

        if (reorderedCapabilities.isEmpty()) {
            Logger.w(tag = "CodecPreferences", message = "No valid codec capabilities created, will use WebRTC defaults")
            return null
        }

        Logger.d(tag = "CodecPreferences", message = "Final codec order: ${reorderedCapabilities.map { it.mimeType }}")
        return reorderedCapabilities
    }

    /**
     * Finds a matching RtpParameters.Codec from available codecs based on the preferred codec.
     * Matches on codec name (MIME type), clock rate, and optionally channel count.
     *
     * @param preferredCodec The preferred audio codec to match
     * @param availableCodecs List of available RTP codecs from the receiver
     * @param usedCodecs Set of codecs already matched to avoid duplicates
     * @return The matching RtpParameters.Codec, or null if no match found
     */
    fun findMatchingRtpCodec(
        preferredCodec: AudioCodec,
        availableCodecs: List<RtpParameters.Codec>,
        usedCodecs: Set<RtpParameters.Codec>
    ): RtpParameters.Codec? {
        // Extract codec name from MIME type (e.g., "audio/opus" -> "opus")
        val preferredName = preferredCodec.mimeType.substringAfter("/").lowercase()

        return availableCodecs.firstOrNull { codec ->
            codec !in usedCodecs &&
            codec.name.lowercase() == preferredName &&
            codec.clockRate == preferredCodec.clockRate &&
            (codec.numChannels == null || codec.numChannels == preferredCodec.channels)
        }
    }

    /**
     * Converts an RtpParameters.Codec to an RtpCapabilities.CodecCapability.
     * Assumes audio codecs since we're working with audio transceivers.
     *
     * @param rtpCodec The RTP codec to convert
     * @return The converted codec capability, or null if conversion fails
     */
    fun convertRtpCodecToCapability(rtpCodec: RtpParameters.Codec): RtpCapabilities.CodecCapability? {
        return try {
            val capability = RtpCapabilities.CodecCapability()
            // Construct full MIME type for audio (e.g., "audio/opus")
            // We assume audio since we're working with the audio transceiver
            capability.mimeType = "audio/${rtpCodec.name}"
            capability.clockRate = rtpCodec.clockRate ?: 0
            capability.numChannels = rtpCodec.numChannels ?: 1
            capability.parameters = rtpCodec.parameters ?: emptyMap()
            capability
        } catch (e: Exception) {
            Logger.e(tag = "CodecPreferences", message = "Error converting RtpCodec to Capability: ${e.message}")
            null
        }
    }

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
}
