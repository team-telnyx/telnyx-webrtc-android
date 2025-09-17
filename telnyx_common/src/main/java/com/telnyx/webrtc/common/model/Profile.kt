package com.telnyx.webrtc.common.model

import com.telnyx.webrtc.sdk.model.Region

/**
 * Data class that represents the user profile.
 *
 * @param isUserLoggedIn True if the user is logged in, false otherwise.
 * @param sipUsername The SIP username.
 * @param sipPass The SIP password.
 * @param callerIdName The caller ID name.
 * @param callerIdNumber The caller ID number.
 * @param isDev True if the user is a developer, false otherwise.
 * @param fcmToken The FCM token.
 * @param region The selected region for WebRTC connections.
 * @param isDebug True if debug mode is enabled, false otherwise.
 * When this option is on it allows the SDK to collect WebRTC debug information.
 * That information is stored in the Telnyx customer portal
 * @param forceRelayCandidate True to force TURN relay for peer connections to prevent local network access permission popup.
 */
data class Profile(
    val sipUsername: String? = null,
    val sipPass: String? = null,
    val sipToken: String? = null,
    val callerIdName: String? = null,
    val callerIdNumber: String? = null,
    var isUserLoggedIn: Boolean = false,
    var isDev: Boolean = false,
    var fcmToken: String? = null,
    var region: Region = Region.AUTO,
    var forceRelayCandidate: Boolean = false
) {
    fun isToken(): Boolean {
        return sipToken?.trim()?.isNotEmpty() ?: false
    }
}
