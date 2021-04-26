package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName

data class FcmRegistrationResponse (
    @SerializedName("message") val message : String,
)

data class TelnyxNotificationServiceResponse (
    @SerializedName("data") val data : Data
)

data class Data (
    @SerializedName("credential_id") val credential_id : String
)