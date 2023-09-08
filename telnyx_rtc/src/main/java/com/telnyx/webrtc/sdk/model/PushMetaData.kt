package com.telnyx.webrtc.sdk.model

data class PushMetaData(
    val caller_name: String,
    val caller_number: String,
    val call_id: String,
    val rtc_ip: String,
    val rtc_port: Int,
    )
