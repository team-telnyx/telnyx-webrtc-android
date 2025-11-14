/*
 * Copyright © 2025 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Represents audio processing constraints for WebRTC media streams.
 * These constraints control audio processing features that improve call quality
 * by reducing echo, background noise, and normalizing audio levels.
 *
 * Aligns with the W3C MediaTrackConstraints specification for audio.
 *
 * @property echoCancellation Enable/disable echo cancellation. When enabled, removes acoustic
 * echo caused by audio feedback between microphone and speaker. Default: true
 * @property noiseSuppression Enable/disable noise suppression. When enabled, reduces background
 * noise to improve voice clarity. Default: true
 * @property autoGainControl Enable/disable automatic gain control. When enabled, automatically
 * adjusts microphone gain to normalize audio levels. Default: true
 *
 * @see <a href="https://w3c.github.io/mediacapture-main/getusermedia.html#def-constraint-echoCancellation">W3C Echo Cancellation</a>
 * @see <a href="https://w3c.github.io/mediacapture-main/getusermedia.html#dfn-noisesuppression">W3C Noise Suppression</a>
 * @see <a href="https://w3c.github.io/mediacapture-main/getusermedia.html#dfn-autogaincontrol">W3C Auto Gain Control</a>
 */
data class AudioConstraints(
    val echoCancellation: Boolean = true,
    val noiseSuppression: Boolean = true,
    val autoGainControl: Boolean = true
)
