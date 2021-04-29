/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

enum class SocketMethod(var methodName: String) {
    ANSWER("telnyx_rtc.answer"),
    INVITE("telnyx_rtc.invite"),
    BYE("telnyx_rtc.bye"),
    MODIFY("telnyx_rtc.modify"),
    MEDIA("telnyx_rtc.media"),
    LOGIN("login")
}