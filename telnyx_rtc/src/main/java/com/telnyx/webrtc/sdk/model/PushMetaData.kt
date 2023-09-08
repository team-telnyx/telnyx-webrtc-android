package com.telnyx.webrtc.sdk.model

import com.google.gson.annotations.SerializedName

data class PushMetaData(
    @SerializedName("caller_name")
    val callerName: String,
    @SerializedName("caller_number")
    val callerNumber: String,
    @SerializedName("call_id")
    val callId: String,
    @SerializedName("rtc_ip")
    val rtcIP: String,
    @SerializedName("rtc_port")
    val rtcPort: Int,
    )
