package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName

data class CreateCredential (
    @SerializedName("type") val type : String,
    @SerializedName("data") val data : Data
)

data class Data (
    @SerializedName("server_key") val server_server_key : String,
    @SerializedName("device_id") val device_id : String
)