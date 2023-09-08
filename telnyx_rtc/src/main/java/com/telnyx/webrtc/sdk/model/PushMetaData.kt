package com.telnyx.webrtc.sdk.model

import com.google.gson.annotations.SerializedName

data class PushMetaData(
    val caller_name: String,
    val caller_number: String,
    val call_id: String,
    @SerializedName("rtc_ip")
    val rtcIP: String,
    @SerializedName("rtc_port")
    val rtcPort: Int,
    )
