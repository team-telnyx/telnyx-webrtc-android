/*
 * Copyright © 2025 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model
import com.google.gson.annotations.SerializedName

/**
 * Represents an audio codec with its properties.
 *
 * @property channels Number of audio channels (e.g., 1 for mono, 2 for stereo)
 * @property clockRate Sample rate in Hz (e.g., 48000, 8000)
 * @property mimeType MIME type of the codec (e.g., "audio/opus", "audio/PCMA")
 * @property sdpFmtpLine SDP format parameters line (optional)
 */
data class AudioCodec(
    val channels: Int,
    @SerializedName("clockRate")
    val clockRate: Int,
    val mimeType: String,
    val sdpFmtpLine: String? = null
)
