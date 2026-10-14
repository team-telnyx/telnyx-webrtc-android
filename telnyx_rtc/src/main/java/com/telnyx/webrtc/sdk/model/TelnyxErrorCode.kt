/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Named constants for all SDK error codes.
 *
 * Use these instead of hard-coding numeric literals in [TelnyxError] comparisons
 * and [com.telnyx.webrtc.sdk.TelnyxClient.emitError] calls.
 *
 * Mirrors the JS SDK's `TELNYX_ERROR_CODES` constant.
 */
enum class TelnyxErrorCode(val code: Int) {
    // ── SDP errors (400xx) ──
    SDP_CREATE_OFFER_FAILED(40001),
    SDP_CREATE_ANSWER_FAILED(40002),
    SDP_SET_LOCAL_DESCRIPTION_FAILED(40003),
    SDP_SET_REMOTE_DESCRIPTION_FAILED(40004),
    SDP_SEND_FAILED(40005),

    // ── Media / device errors (420xx) ──
    MEDIA_MICROPHONE_PERMISSION_DENIED(42001),
    MEDIA_DEVICE_NOT_FOUND(42002),
    MEDIA_GET_USER_MEDIA_FAILED(42003),
    UNABLE_READ_AUDIO_INPUT_ON_DEVICE_CHANGE(42004),

    // ── Call-control errors (440xx) ──
    HOLD_FAILED(44001),
    INVALID_CALL_PARAMETERS(44002),
    BYE_SEND_FAILED(44003),
    SUBSCRIBE_FAILED(44004),
    PEER_CLOSED_DURING_INIT(44005),

    // ── WebSocket / transport errors (450xx) ──
    WEBSOCKET_CONNECTION_FAILED(45001),
    WEBSOCKET_ERROR(45002),
    RECONNECTION_EXHAUSTED(45003),
    GATEWAY_FAILED(45004),

    // ── Authentication errors (460xx) ──
    LOGIN_FAILED(46001),
    INVALID_CREDENTIALS(46002),
    AUTHENTICATION_REQUIRED(46003),

    // ── ICE restart errors (470xx) ──
    ICE_RESTART_FAILED(47001),

    // ── Network errors (480xx) ──
    NETWORK_OFFLINE(48001),

    // ── Session errors (485xx) ──
    SESSION_NOT_REATTACHED(48501),

    // ── General / catch-all errors (490xx) ──
    UNEXPECTED_ERROR(49001),
}

/**
 * Named constants for all SDK warning codes.
 *
 * Use these instead of hard-coding numeric literals in [TelnyxWarning] comparisons
 * and [com.telnyx.webrtc.sdk.TelnyxClient.emitWarning] calls.
 *
 * Mirrors the JS SDK's `TELNYX_WARNING_CODES` constant.
 */
enum class TelnyxWarningCode(val code: Int) {
    // ── Network quality warnings (310xx) ──
    HIGH_RTT(31001),
    HIGH_JITTER(31002),
    HIGH_PACKET_LOSS(31003),
    LOW_MOS(31004),
    LOW_LOCAL_AUDIO(31005),
    LOW_INBOUND_AUDIO(31006),

    // ── Connection / data-flow warnings (320xx) ──
    LOW_BYTES_RECEIVED(32001),
    LOW_BYTES_SENT(32002),

    // ── Call connection warnings (330xx) ──
    ICE_CONNECTIVITY_LOST(33001),
    ICE_GATHERING_TIMEOUT(33002),
    ICE_GATHERING_EMPTY(33003),
    PEER_CONNECTION_FAILED(33004),

    // ── Authentication warnings (340xx) ──
    TOKEN_EXPIRING_SOON(34001),

    // ── Session / reconnection warnings (350xx) ──
    UNKNOWN_REATTACHED_SESSION(35002),

    // ── Signaling health warnings (360xx) ──
    SIGNALING_RECOVERY_REQUIRED(36003),
    MEDIA_RECOVERY_REQUIRED(36004),
    RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT(36005),
}
