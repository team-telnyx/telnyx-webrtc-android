package com.telnyx.webrtc.common.model

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
 */
data class Profile(
    val sipUsername: String? = null,
    val sipPass: String? = null,
    val sipToken: String? = null,
    val callerIdName: String? = null,
    val callerIdNumber: String? = null,
    var isUserLoggedIn: Boolean = false,
    var isDev: Boolean = false,
    var fcmToken: String? = null) {
    fun isToken(): Boolean {
        return sipToken?.trim()?.isNotEmpty() ?: false
    }
}
