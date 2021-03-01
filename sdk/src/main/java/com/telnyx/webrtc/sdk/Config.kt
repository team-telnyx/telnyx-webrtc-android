package com.telnyx.webrtc.sdk

internal class Config {
    companion object {
        private const val PROD_SIGNALING_SERVER = "wss://rtc.telnyx.com:14938"
        private const val DEV_SIGNALING_SERVER = "wss://rtcdev.telnyx.com:14938"
        const val SIGNALING_SERVER = PROD_SIGNALING_SERVER

        const val DEFAULT_TURN = "turn:turn.telnyx.com:3478?transport=tcp"
        const val DEFAULT_STUN = "stun:stun.telnyx.com:3843"
        const val TEST_USERNAME = "testuser"
        const val TEST_PASSWORD = "testpassword"
    }
}