package com.telnyx.webrtc.sdk.model

import com.telnyx.webrtc.sdk.Config

data class TxServerConfiguration(
    val host: String = Config.TELNYX_PROD_HOST_ADDRESS,
    val port: Int = Config.TELNYX_PORT,
    val turn: String = Config.DEFAULT_TURN,
    val stun: String = Config.DEFAULT_STUN
)
