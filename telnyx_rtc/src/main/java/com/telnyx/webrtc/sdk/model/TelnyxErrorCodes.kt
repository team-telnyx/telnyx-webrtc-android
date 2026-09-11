/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Named constants for SDK error codes.
 *
 * SDK consumers should use these instead of hard-coding numeric literals
 * in comparisons against error events.
 *
 * Code ranges:
 * - 400xx — SDP negotiation errors
 * - 420xx — Media / device errors
 * - 440xx — Call-control errors (hold, bye, subscribe, call params)
 * - 450xx — WebSocket / transport errors
 * - 460xx — Authentication errors
 * - 470xx — ICE restart errors
 * - 480xx — Network errors
 * - 485xx — Session errors
 * - 490xx — General / catch-all errors
 */
object TelnyxErrorCodes {
    // ── SDP errors (400xx) ──────────────────────────────────────────────
    const val SDP_CREATE_OFFER_FAILED = 40001
    const val SDP_CREATE_ANSWER_FAILED = 40002
    const val SDP_SET_LOCAL_DESCRIPTION_FAILED = 40003
    const val SDP_SET_REMOTE_DESCRIPTION_FAILED = 40004
    const val SDP_SEND_FAILED = 40005

    // ── Media / device errors (420xx) ───────────────────────────────────
    const val MEDIA_MICROPHONE_PERMISSION_DENIED = 42001
    const val MEDIA_DEVICE_NOT_FOUND = 42002
    const val MEDIA_GET_USER_MEDIA_FAILED = 42003

    // ── Call-control errors (440xx) ─────────────────────────────────────
    const val HOLD_FAILED = 44001
    const val INVALID_CALL_PARAMETERS = 44002
    const val BYE_SEND_FAILED = 44003
    const val SUBSCRIBE_FAILED = 44004
    const val PEER_CLOSED_DURING_INIT = 44005

    // ── WebSocket / transport errors (450xx) ──────────────────────────
    const val WEBSOCKET_CONNECTION_FAILED = 45001
    const val WEBSOCKET_ERROR = 45002
    const val RECONNECTION_EXHAUSTED = 45003
    const val GATEWAY_FAILED = 45004

    // ── Authentication errors (460xx) ───────────────────────────────────
    const val LOGIN_FAILED = 46001
    const val INVALID_CREDENTIALS = 46002
    const val AUTHENTICATION_REQUIRED = 46003

    // ── ICE restart errors (470xx) ─────────────────────────────────────
    const val ICE_RESTART_FAILED = 47001

    // ── Network errors (480xx) ──────────────────────────────────────────
    const val NETWORK_OFFLINE = 48001

    // ── Session errors (485xx) ───────────────────────────────────────────
    const val SESSION_NOT_REATTACHED = 48501

    // ── General / catch-all errors (490xx) ──────────────────────────────
    const val UNEXPECTED_ERROR = 49001
}
