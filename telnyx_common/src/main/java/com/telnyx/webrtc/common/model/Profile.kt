package com.telnyx.webrtc.common.model

data class Profile(
        val sipUsername: String? = null,
        val sipPass: String? = null,
        val sipToken: String? = null,
        val callerIdName: String? = null,
        val callerIdNumber: String? = null,
        var isUserLogin: Boolean = false,
        var isDev: Boolean = false) {
    fun isToken(): Boolean {
        return sipToken?.trim()?.isNotEmpty() ?: false
    }
}
