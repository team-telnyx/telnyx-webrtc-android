/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Static registry of all structured error definitions in the Telnyx Android SDK.
 *
 * This is a direct port of the JS SDK's `errors.ts` registry (`_SDK_ERRORS`).
 * Each entry maps a numeric code to a [TelnyxError] containing the error name,
 * message, description, causes, solutions, and fatal flag.
 *
 * Emit sites in the SDK look up the definition by code and emit the resulting
 * [TelnyxError] through [com.telnyx.webrtc.sdk.TelnyxClient.errorFlow].
 *
 * ## Code ranges
 *
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
object TelnyxErrorRegistry {

    // ── SDP errors (400xx) ──────────────────────────────────────────────

    private val SDP_CREATE_OFFER_FAILED = TelnyxError(
        code = 40001,
        name = "SDP_CREATE_OFFER_FAILED",
        message = "Failed to create call offer",
        description = "The SDK was unable to generate a local SDP offer. This typically indicates a WebRTC API error or invalid media constraints.",
        causes = listOf(
            "WebRTC API error",
            "Missing or invalid media constraints",
        ),
        solutions = listOf(
            "Check microphone permissions",
            "Verify ICE server configuration",
        ),
        fatal = true,
    )

    private val SDP_CREATE_ANSWER_FAILED = TelnyxError(
        code = 40002,
        name = "SDP_CREATE_ANSWER_FAILED",
        message = "Failed to answer the call",
        description = "The SDK was unable to generate a local SDP answer. The remote offer may be invalid or the peer connection state may be inconsistent.",
        causes = listOf(
            "WebRTC API error",
            "Invalid remote SDP offer",
        ),
        solutions = listOf(
            "Retry the call",
            "Check WebRTC compatibility",
        ),
        fatal = true,
    )

    private val SDP_SET_LOCAL_DESCRIPTION_FAILED = TelnyxError(
        code = 40003,
        name = "SDP_SET_LOCAL_DESCRIPTION_FAILED",
        message = "Failed to apply local call settings",
        description = "setLocalDescription() was rejected. The generated SDP may be malformed or the peer connection state may be inconsistent.",
        causes = listOf(
            "Malformed SDP",
            "Peer connection state inconsistency",
        ),
        solutions = listOf(
            "Retry the call",
        ),
        fatal = true,
    )

    private val SDP_SET_REMOTE_DESCRIPTION_FAILED = TelnyxError(
        code = 40004,
        name = "SDP_SET_REMOTE_DESCRIPTION_FAILED",
        message = "Failed to apply remote call settings",
        description = "setRemoteDescription() was rejected. The remote SDP may be malformed or contain unsupported codecs.",
        causes = listOf(
            "Malformed remote SDP",
            "Codec mismatch",
        ),
        solutions = listOf(
            "Retry the call",
            "Check codec configuration",
        ),
        fatal = true,
    )

    private val SDP_SEND_FAILED = TelnyxError(
        code = 40005,
        name = "SDP_SEND_FAILED",
        message = "Failed to send call data to server",
        description = "The Invite or Answer message could not be delivered via the signaling WebSocket. The connection may have been lost.",
        causes = listOf(
            "WebSocket connection lost",
            "Server error",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Retry the call",
        ),
        fatal = true,
    )

    // ── Media / device errors (420xx) ───────────────────────────────────

    private val MEDIA_MICROPHONE_PERMISSION_DENIED = TelnyxError(
        code = 42001,
        name = "MEDIA_MICROPHONE_PERMISSION_DENIED",
        message = "Microphone access denied",
        description = "The user or operating system denied microphone permission.",
        causes = listOf(
            "User denied permission prompt",
            "OS-level microphone access disabled",
        ),
        solutions = listOf(
            "Ask user to grant microphone permission in system settings",
        ),
        fatal = true,
    )

    private val MEDIA_DEVICE_NOT_FOUND = TelnyxError(
        code = 42002,
        name = "MEDIA_DEVICE_NOT_FOUND",
        message = "No microphone found",
        description = "The requested audio input device is not available. No microphone is connected, the device was disconnected, or an invalid deviceId was specified.",
        causes = listOf(
            "No microphone connected",
            "Device was disconnected",
            "Invalid deviceId",
        ),
        solutions = listOf(
            "Check that a microphone is connected",
            "Select a valid audio input device",
        ),
        fatal = true,
    )

    private val MEDIA_GET_USER_MEDIA_FAILED = TelnyxError(
        code = 42003,
        name = "MEDIA_GET_USER_MEDIA_FAILED",
        message = "Failed to access microphone",
        description = "getUserMedia() was rejected for an unexpected reason. The device may be in use by another application or the system encountered an internal error.",
        causes = listOf(
            "System error",
            "Device in use by another application",
        ),
        solutions = listOf(
            "Close other applications using the microphone",
            "Retry",
        ),
        fatal = true,
    )

    private val UNABLE_READ_AUDIO_INPUT_ON_DEVICE_CHANGE = TelnyxError(
        code = 42004,
        name = "UNABLE_READ_AUDIO_INPUT_ON_DEVICE_CHANGE",
        message = "Unable to read audio input settings after device change",
        description = "The audio input track was replaced successfully, but reading its settings failed. The call continues with the new track.",
        causes = listOf(
            "MediaStreamTrack.getSettings() threw after audio input replacement",
        ),
        solutions = listOf(
            "No call restart is required; inspect the original error if device identification remains unavailable",
        ),
        fatal = false,
    )

    // ── Call-control errors (440xx) ─────────────────────────────────────

    private val HOLD_FAILED = TelnyxError(
        code = 44001,
        name = "HOLD_FAILED",
        message = "Failed to hold the call",
        description = "The server rejected or did not respond to the hold request. The WebSocket connection may have been lost during the operation.",
        causes = listOf(
            "Server error",
            "WebSocket connection lost during hold",
        ),
        solutions = listOf(
            "Retry the hold operation",
            "Check network connectivity",
        ),
        fatal = false,
    )

    private val INVALID_CALL_PARAMETERS = TelnyxError(
        code = 44002,
        name = "INVALID_CALL_PARAMETERS",
        message = "Invalid call parameters",
        description = "The call could not be initiated because required parameters are missing or invalid.",
        causes = listOf(
            "Missing destination number in call options",
            "Invalid or empty call parameters",
        ),
        solutions = listOf(
            "Provide a valid destination number when calling newCall()",
            "Check the call options object for required fields",
        ),
        fatal = true,
    )

    private val BYE_SEND_FAILED = TelnyxError(
        code = 44003,
        name = "BYE_SEND_FAILED",
        message = "Failed to hang up cleanly",
        description = "The hangup signal could not be delivered to the server. The call was terminated locally but the server may not be aware.",
        causes = listOf(
            "WebSocket connection lost before BYE sent",
        ),
        solutions = listOf(
            "No action needed — call is terminated locally",
            "Check network connectivity",
        ),
        fatal = false,
    )

    private val SUBSCRIBE_FAILED = TelnyxError(
        code = 44004,
        name = "SUBSCRIBE_FAILED",
        message = "Failed to subscribe to call events",
        description = "The Verto subscribe request for the call channel failed. This may prevent receiving call state updates from the server.",
        causes = listOf(
            "WebSocket connection lost during subscribe",
            "Server rejected the subscription request",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Retry the call",
        ),
        fatal = false,
    )

    private val PEER_CLOSED_DURING_INIT = TelnyxError(
        code = 44005,
        name = "PEER_CLOSED_DURING_INIT",
        message = "Call was closed during setup",
        description = "The PeerConnection was closed while peer.init() was still running. This is a race condition: an async operation such as setRemoteDescription or getUserMedia yielded control, and close() ran during that gap.",
        causes = listOf(
            "call.hangup() was called while the call was still setting up",
            "A WebSocket Bye message arrived during SDP negotiation",
            "User clicked hangup/decline before media permissions were granted",
        ),
        solutions = listOf(
            "This is expected if the user intentionally hung up during setup — no action needed",
            "If this happens frequently without user action, check for automatic hangup triggers that may fire too early",
        ),
        fatal = true,
    )

    // ── WebSocket / transport errors (450xx) ────────────────────────────

    private val WEBSOCKET_CONNECTION_FAILED = TelnyxError(
        code = 45001,
        name = "WEBSOCKET_CONNECTION_FAILED",
        message = "Unable to connect to server",
        description = "The WebSocket connection to the signaling server could not be established. The server may be unreachable, the URL may be incorrect, or a firewall may be blocking the connection.",
        causes = listOf(
            "Server unreachable",
            "Incorrect WebSocket URL",
            "Firewall blocking WebSocket connections",
            "Network interruption",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Verify the signaling server URL",
            "Ensure WebSocket connections are not blocked by a firewall",
        ),
        fatal = true,
    )

    private val WEBSOCKET_ERROR = TelnyxError(
        code = 45002,
        name = "WEBSOCKET_ERROR",
        message = "Connection to server lost",
        description = "An error occurred on the WebSocket connection after it was established. The connection may have been dropped due to network issues or server-side closure.",
        causes = listOf(
            "Network interruption",
            "Server closed the connection",
            "Idle timeout",
        ),
        solutions = listOf(
            "Check network connectivity",
            "SDK will attempt automatic reconnection if configured",
        ),
        fatal = false,
    )

    private val RECONNECTION_EXHAUSTED = TelnyxError(
        code = 45003,
        name = "RECONNECTION_EXHAUSTED",
        message = "Unable to reconnect to server",
        description = "All automatic reconnection attempts have been exhausted. The SDK tried to re-establish the WebSocket connection multiple times but failed on every attempt.",
        causes = listOf(
            "Prolonged network outage",
            "Server unreachable",
            "Firewall or proxy blocking reconnection",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Call client.disconnect() and client.connect() to manually retry",
            "Notify the user that the connection was lost",
        ),
        fatal = true,
    )

    private val GATEWAY_FAILED = TelnyxError(
        code = 45004,
        name = "GATEWAY_FAILED",
        message = "Gateway connection failed",
        description = "The upstream gateway reported a FAILED, FAIL_WAIT, or TIMEOUT state. The signaling server could not establish or maintain a connection to the gateway. When autoReconnect is disabled, this is immediately fatal. When enabled, the SDK will retry until RECONNECTION_EXHAUSTED.",
        causes = listOf(
            "Gateway down or unreachable",
            "Server-side infrastructure issue",
            "Network partition between signaling server and gateway",
        ),
        solutions = listOf(
            "Wait for automatic reconnection (if autoReconnect is enabled)",
            "Call client.disconnect() and client.connect() to manually retry",
            "Check Telnyx service status",
        ),
        fatal = false,
    )

    // ── Authentication errors (460xx) ───────────────────────────────────

    private val LOGIN_FAILED = TelnyxError(
        code = 46001,
        name = "LOGIN_FAILED",
        message = "Authentication failed",
        description = "The login request was rejected by the server. The credentials may be invalid, expired, or the account may be suspended.",
        causes = listOf(
            "Invalid credentials (username/password or token)",
            "Expired authentication token",
            "Account suspended or disabled",
        ),
        solutions = listOf(
            "Verify credentials",
            "Generate a new authentication token",
            "Check account status",
        ),
        fatal = true,
    )

    private val INVALID_CREDENTIALS = TelnyxError(
        code = 46002,
        name = "INVALID_CREDENTIALS",
        message = "Invalid credential parameters",
        description = "The SDK rejected the login options before sending any request to the server. The credentials object is missing required fields or has an invalid structure.",
        causes = listOf(
            "Missing login and password fields",
            "Missing or malformed authentication token",
            "Invalid combination of credential fields in the options object",
        ),
        solutions = listOf(
            "Provide valid login/password or a valid authentication token",
            "Check the CredentialConfig/TokenConfig against the documentation",
            "Ensure the credential object matches one of the supported auth modes",
        ),
        fatal = true,
    )

    private val AUTHENTICATION_REQUIRED = TelnyxError(
        code = 46003,
        name = "AUTHENTICATION_REQUIRED",
        message = "Authentication required",
        description = "The server rejected a request because the session is not authenticated. This can happen when the client sends a message before login completes, after a token expires mid-session, or after the server drops the authenticated state.",
        causes = listOf(
            "Message sent before login completed",
            "Authentication token expired during the session",
            "Server-side session was invalidated",
            "WebSocket reconnected but re-authentication did not complete",
        ),
        solutions = listOf(
            "Ensure the client is fully logged in before sending messages",
            "Re-authenticate using fresh credentials",
            "Listen for client ready callback before making calls",
        ),
        fatal = false,
    )

    // ── ICE restart errors (470xx) ─────────────────────────────────────

    private val ICE_RESTART_FAILED = TelnyxError(
        code = 47001,
        name = "ICE_RESTART_FAILED",
        message = "ICE restart failed",
        description = "The ICE restart Modify request could not be sent or the server returned an error. The media path could not be recovered via ICE restart.",
        causes = listOf(
            "WebSocket connection lost during ICE restart",
            "Server rejected the Modify request",
            "Timeout waiting for server response",
        ),
        solutions = listOf(
            "The call may recover via WebSocket reconnect + Attach",
            "If the call does not recover, hang up and retry",
        ),
        fatal = false,
    )

    // ── Network errors (480xx) ──────────────────────────────────────────

    private val NETWORK_OFFLINE = TelnyxError(
        code = 48001,
        name = "NETWORK_OFFLINE",
        message = "Device is offline",
        description = "The device has lost network connectivity. All WebSocket and media connections will fail until the network is restored.",
        causes = listOf(
            "Wi-Fi or ethernet disconnected",
            "Airplane mode enabled",
            "Network interface went down",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Reconnect to Wi-Fi or ethernet",
            "Disable airplane mode",
        ),
        fatal = false,
    )

    // ── Session errors (485xx) ───────────────────────────────────────────

    private val SESSION_NOT_REATTACHED = TelnyxError(
        code = 48501,
        name = "SESSION_NOT_REATTACHED",
        message = "Active call lost after reconnect",
        description = "The WebSocket reconnected successfully but the server did not reattach the active call session. The server no longer knows about the call, so any subsequent call-control operation will fail.",
        causes = listOf(
            "Server-side session expired during the disconnection window",
            "Reconnect token was invalidated",
            "Backend restarted or lost in-memory call state",
        ),
        solutions = listOf(
            "Terminate the local call and notify the user",
            "Start a new call",
            "Investigate why the session was not preserved on the server",
        ),
        fatal = true,
    )

    // ── General / catch-all errors (490xx) ──────────────────────────────

    private val UNEXPECTED_ERROR = TelnyxError(
        code = 49001,
        name = "UNEXPECTED_ERROR",
        message = "An unexpected error occurred",
        description = "An error was thrown that does not match any known SDK error category. This is a catch-all for unclassified failures.",
        causes = listOf(
            "Unknown or unhandled error condition",
        ),
        solutions = listOf(
            "Capture the thrown exception for the underlying cause",
            "Report the issue if it persists",
        ),
        fatal = false,
    )

    /**
     * Lookup table: error code → [TelnyxError] definition.
     */
    @Suppress("MagicNumber") // Registry lookup-table keys ARE the named constants
    private val registry: Map<Int, TelnyxError> = mapOf(
        40001 to SDP_CREATE_OFFER_FAILED,
        40002 to SDP_CREATE_ANSWER_FAILED,
        40003 to SDP_SET_LOCAL_DESCRIPTION_FAILED,
        40004 to SDP_SET_REMOTE_DESCRIPTION_FAILED,
        40005 to SDP_SEND_FAILED,
        42001 to MEDIA_MICROPHONE_PERMISSION_DENIED,
        42002 to MEDIA_DEVICE_NOT_FOUND,
        42003 to MEDIA_GET_USER_MEDIA_FAILED,
        42004 to UNABLE_READ_AUDIO_INPUT_ON_DEVICE_CHANGE,
        44001 to HOLD_FAILED,
        44002 to INVALID_CALL_PARAMETERS,
        44003 to BYE_SEND_FAILED,
        44004 to SUBSCRIBE_FAILED,
        44005 to PEER_CLOSED_DURING_INIT,
        45001 to WEBSOCKET_CONNECTION_FAILED,
        45002 to WEBSOCKET_ERROR,
        45003 to RECONNECTION_EXHAUSTED,
        45004 to GATEWAY_FAILED,
        46001 to LOGIN_FAILED,
        46002 to INVALID_CREDENTIALS,
        46003 to AUTHENTICATION_REQUIRED,
        47001 to ICE_RESTART_FAILED,
        48001 to NETWORK_OFFLINE,
        48501 to SESSION_NOT_REATTACHED,
        49001 to UNEXPECTED_ERROR,
    )

    /**
     * Returns the [TelnyxError] definition for the given [code], or null if not found.
     */
    fun get(code: Int): TelnyxError? = registry[code]

    /**
     * Returns all registered error codes.
     */
    fun codes(): Set<Int> = registry.keys
}
