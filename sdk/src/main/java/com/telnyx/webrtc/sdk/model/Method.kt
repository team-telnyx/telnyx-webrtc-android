package com.telnyx.webrtc.sdk.model

enum class Method(var methodName: String) {
    ANSWER("telnyx_rtc.answer"),
    INVITE("telnyx_rtc.invite"),
    BYE("telnyx_rtc.bye"),
    MODIFY("telnyx_rtc.modify"),
    LOGIN("login")
}