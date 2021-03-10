package com.telnyx.webrtc.sdk

data class TelnyxConfig(
        val sipUser: String,
        val sipPassword: String,
        val sipCallerIDName: String?,
        val sipCallerIDNumber: String?,
        val ringtone: Int?,
        val ringBackTone: Int?)