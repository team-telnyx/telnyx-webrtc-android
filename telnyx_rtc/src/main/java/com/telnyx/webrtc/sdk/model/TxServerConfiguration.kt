package com.telnyx.webrtc.sdk.model

import com.telnyx.webrtc.sdk.Config

data class TxServerConfiguration(
    val host: String = Config.TELNYX_PROD_HOST_ADDRESS,
    val port: Int = Config.TELNYX_PORT,
    val turn: String = Config.DEFAULT_TURN,
    val stun: String = Config.DEFAULT_STUN
) {
    companion object {
        /**
         * Creates a production server configuration with default production values.
         */
        fun production(): TxServerConfiguration = TxServerConfiguration(
            host = Config.TELNYX_PROD_HOST_ADDRESS,
            port = Config.TELNYX_PORT,
            turn = Config.DEFAULT_TURN,
            stun = Config.DEFAULT_STUN
        )

        /**
         * Creates a development server configuration with default development values.
         */
        fun development(): TxServerConfiguration = TxServerConfiguration(
            host = Config.TELNYX_DEV_HOST_ADDRESS,
            port = Config.TELNYX_PORT,
            turn = Config.DEV_TURN,
            stun = Config.DEV_STUN
        )
    }
}
