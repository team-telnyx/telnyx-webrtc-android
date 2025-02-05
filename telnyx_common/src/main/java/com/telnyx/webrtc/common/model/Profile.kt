package com.telnyx.webrtc.common.model

data class Profile(var isUserLogin: Boolean = false,
        val sipUsername: String,
        val sipPass: String,
        val callerIdName: String?,
        val callerIdNumber: String?,
        var isDev: Boolean = false)
