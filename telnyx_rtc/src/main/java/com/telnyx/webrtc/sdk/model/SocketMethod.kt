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
    INVITE("telnyx_rtc.invite"),
    BYE("telnyx_rtc.bye"),
    MODIFY("telnyx_rtc.modify"),
    MEDIA("telnyx_rtc.media"),
    INFO("telnyx_rtc.info"),
    RINGING("telnyx_rtc.ringing"),
    LOGIN("login")
}