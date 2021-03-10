package com.telnyx.webrtc.sdk

sealed class TelnyxConfig

data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val ringtone: Int?,
    val ringBackTone: Int?)

data class TokenConfig(
    val sipToken: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val ringtone: Int?,
    val ringBackTone: Int?)
