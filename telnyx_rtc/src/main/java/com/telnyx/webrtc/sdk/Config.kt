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
    /**
     * Production TURNS server (TLS over TCP on port 443).
     * Last-resort fallback for restrictive firewalls that block non-443 traffic.
     *
     * TURNS URLs intentionally omit the transport parameter, matching the JS SDK.
     */
    const val DEFAULT_TURNS_443 = "turns:turn.telnyx.com:443"
    const val SECONDARY_TURNS_443 = "turns:turn2.telnyx.com:443"

    // Development ICE servers
    const val DEV_STUN = "stun:stundev.telnyx.com:3478"
    const val DEV_TURN_UDP = "turn:turndev.telnyx.com:3478?transport=udp"
    const val DEV_TURN = "turn:turndev.telnyx.com:3478?transport=tcp"
    const val DEV_TURNS_443 = "turns:turndev.telnyx.com:443"

    // Google STUN server for redundancy
    const val GOOGLE_STUN = "stun:stun.l.google.com:19302"

    const val USERNAME = "testuser"
    const val PASSWORD = "testpassword"
}
