/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

internal class Config {
    companion object {
        const val TELNYX_HOST_ADDRESS = "rtc.telnyx.com"
        const val TELNYX_PORT = 14938
        const val DEFAULT_TURN = "turn:turn.telnyx.com:3478?transport=tcp"
        const val DEFAULT_STUN = "stun:stun.telnyx.com:3843"
        var USERNAME = "user"
        var PASSWORD = "password"
    }
}