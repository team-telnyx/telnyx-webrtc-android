/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Represents an audio codec with its name and priority.
 *
 * @property name The name of the codec (e.g., "opus", "PCMU", "PCMA", "G722")
 * @property priority The priority of the codec (higher values indicate higher priority)
 */
data class AudioCodec(
    val name: String,
    val priority: Int = 0
) {
    companion object {
        /**
         * Common audio codecs supported by WebRTC
         */
        val OPUS = AudioCodec("opus", 100)
        val PCMU = AudioCodec("PCMU", 90)
        val PCMA = AudioCodec("PCMA", 85)
        val G722 = AudioCodec("G722", 80)
        val G729 = AudioCodec("G729", 75)
        val GSM = AudioCodec("GSM", 70)
        val TELEPHONE_EVENT = AudioCodec("telephone-event", 60)
        
        /**
         * Returns a list of commonly supported audio codecs
         */
        fun getCommonCodecs(): List<AudioCodec> = listOf(
            OPUS, PCMU, PCMA, G722, G729, GSM, TELEPHONE_EVENT
        )
    }
}
