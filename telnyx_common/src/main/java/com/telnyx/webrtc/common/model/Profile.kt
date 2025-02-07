package com.telnyx.webrtc.common.model

/**
 * Data class that represents the user profile.
 *
 * @param isUserLogin True if the user is logged in, false otherwise.
 * @param sipUsername The SIP username.
 * @param sipPass The SIP password.
 * @param callerIdName The caller ID name.
 * @param callerIdNumber The caller ID number.
 * @param isDev True if the user is a developer, false otherwise.
 */
data class Profile(var isUserLogin: Boolean = false,
        val sipUsername: String,
        val sipPass: String,
        val callerIdName: String?,
        val callerIdNumber: String?,
        var isDev: Boolean = false)
