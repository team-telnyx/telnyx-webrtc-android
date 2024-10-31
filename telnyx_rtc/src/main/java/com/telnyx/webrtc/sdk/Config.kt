/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */
package com.telnyx.webrtc.sdk

internal object Config {
    const val TELNYX_PROD_HOST_ADDRESS = "rtc.telnyx.com"
    const val TELNYX_DEV_HOST_ADDRESS = "rtcdev.telnyx.com"
    const val TELNYX_PORT = 443
    const val DEFAULT_TURN = "turn:turn.telnyx.com:3478?transport=tcp"
    const val DEFAULT_STUN = "stun:stun.telnyx.com:3478"
    var USERNAME = "testuser"
    var PASSWORD = "testpassword"
}
