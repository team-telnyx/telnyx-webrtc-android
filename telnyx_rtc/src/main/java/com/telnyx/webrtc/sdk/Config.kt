/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

internal object Config {
    const val TELNYX_PROD_HOST_ADDRESS = "rtc.telnyx.com"
    const val TELNYX_DEV_HOST_ADDRESS = "rtcdev.telnyx.com"
    const val TELNYX_PORT = 443

    // Production ICE servers
    const val DEFAULT_STUN = "stun:stun.telnyx.com:3478"
    const val DEFAULT_TURN_UDP = "turn:turn.telnyx.com:3478?transport=udp"
    const val DEFAULT_TURN = "turn:turn.telnyx.com:3478?transport=tcp"

    // Development ICE servers
    const val DEV_STUN = "stun:stundev.telnyx.com:3478"
    const val DEV_TURN_UDP = "turn:turndev.telnyx.com:3478?transport=udp"
    const val DEV_TURN = "turn:turndev.telnyx.com:3478?transport=tcp"

    // Google STUN server for redundancy
    const val GOOGLE_STUN = "stun:stun.l.google.com:19302"

    const val USERNAME = "testuser"
    const val PASSWORD = "testpassword"
}
