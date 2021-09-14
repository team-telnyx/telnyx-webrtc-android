package com.telnyx.webrtc.sdk.model

data class TelnyxPushNotification(
    val metaData: PushMetaData,
    val message: String?
)

data class PushMetaData(
    val caller_name: String,
    val caller_number: String,
    val call_id: String
)
