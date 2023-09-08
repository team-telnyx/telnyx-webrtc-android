package com.telnyx.webrtc.sdk.model

import java.io.Serializable

data class TxPushIPConfig(
    val rtcIP: String,
    val rtcPort: Int
) : Serializable
