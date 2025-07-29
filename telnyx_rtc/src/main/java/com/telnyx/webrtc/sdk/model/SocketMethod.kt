/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to detail the Method property of the response from the Telnyx WEBRTC client.
 * with the given [methodName]
 *
 * @param methodName is the Telnyx representation of the method, eg. telnyx_rtc.answer -> ANSWER
 *
 * @property ANSWER the call has been answered by the destination
 * @property INVITE send/receive an invitation that can then be answered or rejected
 * @property BYE a user has ended the call
 * @property MODIFY a modifier that allows the user to hold the call, etc
 * @property MEDIA received media from destination, such as a dialtone
 * @property MEDIA send information to the destination such as DTMF
 * @property LOGIN the response to a login request.
 */
enum class SocketMethod(var methodName: String) {
    ANSWER("telnyx_rtc.answer"),
    ATTACH("telnyx_rtc.attach"),
    INVITE("telnyx_rtc.invite"),
    BYE("telnyx_rtc.bye"),
    MODIFY("telnyx_rtc.modify"),
    MEDIA("telnyx_rtc.media"),
    INFO("telnyx_rtc.info"),
    RINGING("telnyx_rtc.ringing"),
    PINGPONG("telnyx_rtc.ping"),
    CLIENT_READY("telnyx_rtc.clientReady"),
    GATEWAY_STATE("telnyx_rtc.gatewayState"),
    DISABLE_PUSH("telnyx_rtc.disable_push_notification"),
    ATTACH_CALL("telnyx_rtc.attachCalls"),
    LOGIN("login"),
    ANONYMOUS_LOGIN("anonymous_login"),
    AI_CONVERSATION("telnyx_rtc.ai_conversation")
}
