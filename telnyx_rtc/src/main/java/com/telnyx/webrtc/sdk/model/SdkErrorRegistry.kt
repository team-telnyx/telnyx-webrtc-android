/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * SDK error code registry.
 *
 * All entries are surfaced via [TelnyxClient.errorFlow] with level 'error'.
 * Per-entry runtime guarantee lives on [TelnyxError.fatal].
 *
 * Convention for new entries: the safe default is `false` (not terminal).
 * Only set `true` when the SDK genuinely has no recovery path for the error
 * (e.g. SDP failures, invalid credentials, session/call lost).
 */
object SdkErrorRegistry {

    private val registry: Map<Int, TelnyxError> = mapOf(
        // ── SDP errors (400xx) ──────────────────────────────────────────
        TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED,
            name = "SDP_CREATE_OFFER_FAILED",
            message = "Failed to create call offer",
            description = "The SDK was unable to generate a local SDP offer. This typically indicates a WebRTC API error or invalid media constraints.",
            causes = listOf("WebRTC API error", "Missing or invalid media constraints"),
            solutions = listOf("Check microphone permissions", "Verify ICE server configuration"),
            fatal = true
        ),
        TelnyxErrorCodes.SDP_CREATE_ANSWER_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SDP_CREATE_ANSWER_FAILED,
            name = "SDP_CREATE_ANSWER_FAILED",
            message = "Failed to answer the call",
            description = "The SDK was unable to generate a local SDP answer. The remote offer may be invalid or the peer connection state inconsistent.",
            causes = listOf("WebRTC API error", "Invalid remote SDP offer"),
            solutions = listOf("Retry the call", "Check WebRTC compatibility"),
            fatal = true
        ),
        TelnyxErrorCodes.SDP_SET_LOCAL_DESCRIPTION_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SDP_SET_LOCAL_DESCRIPTION_FAILED,
            name = "SDP_SET_LOCAL_DESCRIPTION_FAILED",
            message = "Failed to apply local call settings",
            description = "setLocalDescription() was rejected. The generated SDP may be malformed or the peer connection state may be inconsistent.",
            causes = listOf("Malformed SDP", "Peer connection state inconsistency"),
            solutions = listOf("Retry the call"),
            fatal = true
        ),
        TelnyxErrorCodes.SDP_SET_REMOTE_DESCRIPTION_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SDP_SET_REMOTE_DESCRIPTION_FAILED,
            name = "SDP_SET_REMOTE_DESCRIPTION_FAILED",
            message = "Failed to apply remote call settings",
            description = "setRemoteDescription() was rejected. The remote SDP may be malformed or contain unsupported codecs.",
            causes = listOf("Malformed remote SDP", "Codec mismatch"),
            solutions = listOf("Retry the call", "Check codec configuration"),
            fatal = true
        ),
        TelnyxErrorCodes.SDP_SEND_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SDP_SEND_FAILED,
            name = "SDP_SEND_FAILED",
            message = "Failed to send call data to server",
            description = "The Invite or Answer message could not be delivered via the signaling WebSocket. The connection may have been lost.",
            causes = listOf("WebSocket connection lost", "Server error"),
            solutions = listOf("Check network connectivity", "Retry the call"),
            fatal = true
        ),

        // ── Media / device errors (420xx) ───────────────────────────────
        TelnyxErrorCodes.MEDIA_MICROPHONE_PERMISSION_DENIED to TelnyxError(
            code = TelnyxErrorCodes.MEDIA_MICROPHONE_PERMISSION_DENIED,
            name = "MEDIA_MICROPHONE_PERMISSION_DENIED",
            message = "Microphone access denied",
            description = "The user or operating system denied microphone permission.",
            causes = listOf("User denied permission", "OS-level microphone access disabled"),
            solutions = listOf("Ask user to grant microphone permission in app settings"),
            fatal = true
        ),
        TelnyxErrorCodes.MEDIA_DEVICE_NOT_FOUND to TelnyxError(
            code = TelnyxErrorCodes.MEDIA_DEVICE_NOT_FOUND,
            name = "MEDIA_DEVICE_NOT_FOUND",
            message = "No microphone found",
            description = "The requested audio input device is not available. No microphone is connected or the device was disconnected.",
            causes = listOf("No microphone connected", "Device was disconnected", "Invalid deviceId"),
            solutions = listOf("Check that a microphone is connected", "Select a valid audio input device"),
            fatal = true
        ),
        TelnyxErrorCodes.MEDIA_GET_USER_MEDIA_FAILED to TelnyxError(
            code = TelnyxErrorCodes.MEDIA_GET_USER_MEDIA_FAILED,
            name = "MEDIA_GET_USER_MEDIA_FAILED",
            message = "Failed to access microphone",
            description = "Audio capture failed for an unexpected reason. The device may be in use by another application.",
            causes = listOf("Device in use by another application", "Internal error"),
            solutions = listOf("Close other applications using the microphone", "Retry"),
            fatal = true
        ),

        // ── Call-control errors (440xx) ─────────────────────────────────
        TelnyxErrorCodes.HOLD_FAILED to TelnyxError(
            code = TelnyxErrorCodes.HOLD_FAILED,
            name = "HOLD_FAILED",
            message = "Failed to hold the call",
            description = "The server rejected or did not respond to the hold request.",
            causes = listOf("Server error", "WebSocket connection lost during hold"),
            solutions = listOf("Retry the hold operation", "Check network connectivity"),
            fatal = false
        ),
        TelnyxErrorCodes.INVALID_CALL_PARAMETERS to TelnyxError(
            code = TelnyxErrorCodes.INVALID_CALL_PARAMETERS,
            name = "INVALID_CALL_PARAMETERS",
            message = "Invalid call parameters",
            description = "The call could not be initiated because required parameters are missing or invalid.",
            causes = listOf("Missing destination number", "Invalid call configuration"),
            solutions = listOf("Provide a valid destination number", "Verify call parameters"),
            fatal = false
        ),
        TelnyxErrorCodes.BYE_SEND_FAILED to TelnyxError(
            code = TelnyxErrorCodes.BYE_SEND_FAILED,
            name = "BYE_SEND_FAILED",
            message = "Failed to end the call",
            description = "The BYE message could not be delivered via the signaling WebSocket.",
            causes = listOf("WebSocket connection lost", "Server error"),
            solutions = listOf("Check network connectivity"),
            fatal = false
        ),
        TelnyxErrorCodes.SUBSCRIBE_FAILED to TelnyxError(
            code = TelnyxErrorCodes.SUBSCRIBE_FAILED,
            name = "SUBSCRIBE_FAILED",
            message = "Failed to subscribe to events",
            description = "The server rejected the subscribe request.",
            causes = listOf("Server error", "Invalid subscription parameters"),
            solutions = listOf("Retry the operation"),
            fatal = false
        ),
        TelnyxErrorCodes.PEER_CLOSED_DURING_INIT to TelnyxError(
            code = TelnyxErrorCodes.PEER_CLOSED_DURING_INIT,
            name = "PEER_CLOSED_DURING_INIT",
            message = "Connection closed during call setup",
            description = "The peer connection was closed while the call was being established.",
            causes = listOf("Remote party disconnected", "Network failure during setup"),
            solutions = listOf("Retry the call"),
            fatal = true
        ),

        // ── WebSocket / transport errors (450xx) ────────────────────────
        TelnyxErrorCodes.WEBSOCKET_CONNECTION_FAILED to TelnyxError(
            code = TelnyxErrorCodes.WEBSOCKET_CONNECTION_FAILED,
            name = "WEBSOCKET_CONNECTION_FAILED",
            message = "Failed to connect to server",
            description = "The WebSocket connection to the signaling server could not be established.",
            causes = listOf("Network unreachable", "Server unavailable", "Firewall blocking WebSocket"),
            solutions = listOf("Check network connectivity", "Verify server URL", "Check firewall settings"),
            fatal = false
        ),
        TelnyxErrorCodes.WEBSOCKET_ERROR to TelnyxError(
            code = TelnyxErrorCodes.WEBSOCKET_ERROR,
            name = "WEBSOCKET_ERROR",
            message = "WebSocket error",
            description = "An error occurred on the WebSocket connection.",
            causes = listOf("Network interruption", "Server-side error", "Protocol error"),
            solutions = listOf("Check network connectivity", "SDK will attempt reconnection"),
            fatal = false
        ),
        TelnyxErrorCodes.RECONNECTION_EXHAUSTED to TelnyxError(
            code = TelnyxErrorCodes.RECONNECTION_EXHAUSTED,
            name = "RECONNECTION_EXHAUSTED",
            message = "Reconnection attempts exhausted",
            description = "The SDK has exhausted all reconnection attempts and cannot restore the connection.",
            causes = listOf("Persistent network failure", "Server unavailable"),
            solutions = listOf("Check network connectivity", "Manually reconnect when network is available"),
            fatal = true
        ),
        TelnyxErrorCodes.GATEWAY_FAILED to TelnyxError(
            code = TelnyxErrorCodes.GATEWAY_FAILED,
            name = "GATEWAY_FAILED",
            message = "Gateway failure",
            description = "The WebRTC gateway reported a failure state.",
            causes = listOf("Gateway internal error", "Gateway unavailable"),
            solutions = listOf("Retry connection", "Check server status"),
            fatal = true
        ),

        // ── Authentication errors (460xx) ───────────────────────────────
        TelnyxErrorCodes.LOGIN_FAILED to TelnyxError(
            code = TelnyxErrorCodes.LOGIN_FAILED,
            name = "LOGIN_FAILED",
            message = "Login failed",
            description = "The login attempt was rejected by the server.",
            causes = listOf("Invalid credentials", "Server error"),
            solutions = listOf("Verify credentials", "Check server status"),
            fatal = false
        ),
        TelnyxErrorCodes.INVALID_CREDENTIALS to TelnyxError(
            code = TelnyxErrorCodes.INVALID_CREDENTIALS,
            name = "INVALID_CREDENTIALS",
            message = "Invalid credentials",
            description = "The SIP credentials provided are invalid or expired.",
            causes = listOf("Wrong SIP username or password", "Expired credential", "Credential revoked"),
            solutions = listOf("Verify SIP credentials", "Generate new credentials"),
            fatal = true
        ),
        TelnyxErrorCodes.AUTHENTICATION_REQUIRED to TelnyxError(
            code = TelnyxErrorCodes.AUTHENTICATION_REQUIRED,
            name = "AUTHENTICATION_REQUIRED",
            message = "Authentication required",
            description = "The provided token is invalid, expired, or missing.",
            causes = listOf("Expired token", "Invalid token", "Token not provided"),
            solutions = listOf("Generate a new token", "Verify token configuration"),
            fatal = true
        ),

        // ── ICE restart errors (470xx) ─────────────────────────────────
        TelnyxErrorCodes.ICE_RESTART_FAILED to TelnyxError(
            code = TelnyxErrorCodes.ICE_RESTART_FAILED,
            name = "ICE_RESTART_FAILED",
            message = "ICE restart failed",
            description = "The ICE restart attempt failed to re-establish media connectivity.",
            causes = listOf("Network change", "ICE server unavailable"),
            solutions = listOf("Retry the call"),
            fatal = true
        ),

        // ── Network errors (480xx) ─────────────────────────────────────
        TelnyxErrorCodes.NETWORK_OFFLINE to TelnyxError(
            code = TelnyxErrorCodes.NETWORK_OFFLINE,
            name = "NETWORK_OFFLINE",
            message = "Network is offline",
            description = "The device has no network connectivity.",
            causes = listOf("No network connection", "Airplane mode"),
            solutions = listOf("Check network connectivity", "Disable airplane mode"),
            fatal = false
        ),

        // ── Session errors (485xx) ────────────────────────────────────
        TelnyxErrorCodes.SESSION_NOT_REATTACHED to TelnyxError(
            code = TelnyxErrorCodes.SESSION_NOT_REATTACHED,
            name = "SESSION_NOT_REATTACHED",
            message = "Session was not reattached",
            description = "The SDK could not reattach to the previous session after reconnection.",
            causes = listOf("Session expired on server", "Stale voice_sdk_id"),
            solutions = listOf("Start a new session"),
            fatal = true
        ),

        // ── General / catch-all errors (490xx) ─────────────────────────
        TelnyxErrorCodes.UNEXPECTED_ERROR to TelnyxError(
            code = TelnyxErrorCodes.UNEXPECTED_ERROR,
            name = "UNEXPECTED_ERROR",
            message = "Unexpected error",
            description = "An unexpected error occurred in the SDK.",
            causes = listOf("Internal SDK error", "Unexpected server response"),
            solutions = listOf("Retry the operation", "Contact support if persistent"),
            fatal = false
        )
    )

    /**
     * Look up an error definition by code.
     *
     * @param code The numeric error code
     * @return The [TelnyxError] definition, or null if not found
     */
    fun lookup(code: Int): TelnyxError? = registry[code]

    /**
     * Create a TelnyxError event from a registry definition, optionally
     * overriding the fatal flag and attaching call/session context.
     *
     * @param code The numeric error code
     * @param callId Optional call identifier
     * @param sessionId Optional session identifier
     * @param fatalOverride Override the registry's fatal flag (e.g. media recovery sets false)
     * @return A [TelnyxError] with context attached, or a generic UNEXPECTED_ERROR if code not found
     */
    fun create(
        code: Int,
        callId: UUID? = null,
        sessionId: String? = null,
        fatalOverride: Boolean? = null
    ): TelnyxError {
        val base = registry[code] ?: registry[TelnyxErrorCodes.UNEXPECTED_ERROR]!!
        return base.copy(
            callId = callId,
            sessionId = sessionId,
            fatal = fatalOverride ?: base.fatal
        )
    }
}
