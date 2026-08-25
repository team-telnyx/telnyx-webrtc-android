/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * SDK warning code registry.
 *
 * Warnings represent degraded conditions that may cause unstable
 * connections or bad call experience.
 *
 * Code ranges:
 * - 310xx — Network quality warnings
 * - 320xx — Connection / data-flow warnings
 * - 330xx — Call connection warnings
 * - 340xx — Authentication warnings
 * - 350xx — Session / reconnection warnings
 * - 360xx — Signaling health warnings
 */
object SdkWarningRegistry {

    private val registry: Map<Int, TelnyxWarning> = mapOf(
        // ── Network quality warnings (310xx) ───────────────────────────
        TelnyxWarningCodes.HIGH_RTT to TelnyxWarning(
            code = TelnyxWarningCodes.HIGH_RTT,
            name = "HIGH_RTT",
            message = "High network latency detected",
            description = "Round-trip time (RTT) exceeded the threshold for multiple consecutive samples. High latency causes perceptible audio delays.",
            causes = listOf("Poor network connection", "Geographic distance to media server", "Network congestion"),
            solutions = listOf("Check network connectivity", "Consider using a closer media server")
        ),
        TelnyxWarningCodes.HIGH_JITTER to TelnyxWarning(
            code = TelnyxWarningCodes.HIGH_JITTER,
            name = "HIGH_JITTER",
            message = "High jitter detected",
            description = "Packet inter-arrival time variation exceeded the threshold. High jitter causes choppy audio.",
            causes = listOf("Network congestion", "Route changes", "Insufficient bandwidth"),
            solutions = listOf("Check network stability", "Reduce network load")
        ),
        TelnyxWarningCodes.HIGH_PACKET_LOSS to TelnyxWarning(
            code = TelnyxWarningCodes.HIGH_PACKET_LOSS,
            name = "HIGH_PACKET_LOSS",
            message = "High packet loss detected",
            description = "Packet loss exceeded the threshold for multiple consecutive samples. High loss causes audio gaps and artifacts.",
            causes = listOf("Network congestion", "Unstable connection", "Firewall dropping RTP"),
            solutions = listOf("Check network connectivity", "Verify firewall allows RTP traffic")
        ),
        TelnyxWarningCodes.LOW_MOS to TelnyxWarning(
            code = TelnyxWarningCodes.LOW_MOS,
            name = "LOW_MOS",
            message = "Low call quality detected",
            description = "Mean Opinion Score (MOS) fell below the acceptable threshold.",
            causes = listOf("High latency", "High jitter", "High packet loss", "Low bandwidth"),
            solutions = listOf("Check network conditions", "Reduce concurrent network usage")
        ),
        TelnyxWarningCodes.LOW_LOCAL_AUDIO to TelnyxWarning(
            code = TelnyxWarningCodes.LOW_LOCAL_AUDIO,
            name = "LOW_LOCAL_AUDIO",
            message = "Low outbound audio level",
            description = "Outbound audio level is below the threshold. The microphone may be muted or too quiet.",
            causes = listOf("Microphone muted", "Microphone gain too low", "Microphone hardware issue"),
            solutions = listOf("Check microphone is not muted", "Increase microphone gain", "Check microphone hardware")
        ),
        TelnyxWarningCodes.LOW_INBOUND_AUDIO to TelnyxWarning(
            code = TelnyxWarningCodes.LOW_INBOUND_AUDIO,
            name = "LOW_INBOUND_AUDIO",
            message = "Low inbound audio level",
            description = "Inbound audio level is below the threshold. The remote party may be muted or too quiet.",
            causes = listOf("Remote party muted", "Remote microphone issue", "Media path issue"),
            solutions = listOf("Ask remote party to check microphone", "Verify media connectivity")
        ),

        // ── Connection / data-flow warnings (320xx) ────────────────────
        TelnyxWarningCodes.LOW_BYTES_RECEIVED to TelnyxWarning(
            code = TelnyxWarningCodes.LOW_BYTES_RECEIVED,
            name = "LOW_BYTES_RECEIVED",
            message = "Low data received",
            description = "Bytes received from the remote party are below expected levels.",
            causes = listOf("Remote party not sending audio", "Media path blocked", "NAT/firewall issue"),
            solutions = listOf("Verify remote party is sending audio", "Check firewall settings")
        ),
        TelnyxWarningCodes.LOW_BYTES_SENT to TelnyxWarning(
            code = TelnyxWarningCodes.LOW_BYTES_SENT,
            name = "LOW_BYTES_SENT",
            message = "Low data sent",
            description = "Bytes sent to the remote party are below expected levels.",
            causes = listOf("Microphone not capturing", "Encoder issue", "Media path blocked"),
            solutions = listOf("Check microphone permissions", "Verify encoder is active")
        ),
        TelnyxWarningCodes.RECORDING_UNAVAILABLE to TelnyxWarning(
            code = TelnyxWarningCodes.RECORDING_UNAVAILABLE,
            name = "RECORDING_UNAVAILABLE",
            message = "Recording unavailable",
            description = "Call recording is not available for this call.",
            causes = listOf("Recording not enabled on connection", "Server does not support recording"),
            solutions = listOf("Enable recording on the connection in Portal")
        ),
        TelnyxWarningCodes.RECORDING_BUFFER_OVERFLOW to TelnyxWarning(
            code = TelnyxWarningCodes.RECORDING_BUFFER_OVERFLOW,
            name = "RECORDING_BUFFER_OVERFLOW",
            message = "Recording buffer overflow",
            description = "The recording buffer overflowed, potentially causing data loss.",
            causes = listOf("Insufficient memory", "Slow disk I/O", "Recording buffer too small"),
            solutions = listOf("Free device memory", "Check available storage")
        ),

        // ── Call connection warnings (330xx) ───────────────────────────
        TelnyxWarningCodes.ICE_CONNECTIVITY_LOST to TelnyxWarning(
            code = TelnyxWarningCodes.ICE_CONNECTIVITY_LOST,
            name = "ICE_CONNECTIVITY_LOST",
            message = "ICE connectivity lost",
            description = "ICE connectivity was lost. The media path may be disrupted.",
            causes = listOf("Network change", "ICE candidate pair failure", "NAT timeout"),
            solutions = listOf("SDK will attempt ICE restart", "Check network stability")
        ),
        TelnyxWarningCodes.ICE_GATHERING_TIMEOUT to TelnyxWarning(
            code = TelnyxWarningCodes.ICE_GATHERING_TIMEOUT,
            name = "ICE_GATHERING_TIMEOUT",
            message = "ICE gathering timeout",
            description = "ICE candidate gathering timed out before all candidates were collected.",
            causes = listOf("STUN/TURN server unreachable", "Network firewall blocking STUN/TURN", "DNS resolution failure"),
            solutions = listOf("Verify STUN/TURN server availability", "Check firewall settings")
        ),
        TelnyxWarningCodes.ICE_GATHERING_EMPTY to TelnyxWarning(
            code = TelnyxWarningCodes.ICE_GATHERING_EMPTY,
            name = "ICE_GATHERING_EMPTY",
            message = "No ICE candidates gathered",
            description = "No ICE candidates were gathered, including host candidates.",
            causes = listOf("No network interfaces available", "Network disabled", "WebRTC initialization failure"),
            solutions = listOf("Check network connectivity", "Restart the call")
        ),
        TelnyxWarningCodes.PEER_CONNECTION_FAILED to TelnyxWarning(
            code = TelnyxWarningCodes.PEER_CONNECTION_FAILED,
            name = "PEER_CONNECTION_FAILED",
            message = "Peer connection failed",
            description = "The WebRTC peer connection failed.",
            causes = listOf("ICE failure", "DTLS handshake failure", "Remote party disconnected"),
            solutions = listOf("Retry the call", "Check network connectivity")
        ),
        TelnyxWarningCodes.ONLY_HOST_ICE_CANDIDATES to TelnyxWarning(
            code = TelnyxWarningCodes.ONLY_HOST_ICE_CANDIDATES,
            name = "ONLY_HOST_ICE_CANDIDATES",
            message = "Only host ICE candidates available",
            description = "Only host (local) ICE candidates were gathered. No STUN or TURN candidates are available, which may fail behind NAT.",
            causes = listOf("STUN/TURN server unreachable", "Firewall blocking STUN/TURN", "No STUN/TURN configured"),
            solutions = listOf("Verify STUN/TURN server availability", "Check firewall settings", "Verify ICE server configuration")
        ),
        TelnyxWarningCodes.ANSWER_WHILE_PEER_ACTIVE to TelnyxWarning(
            code = TelnyxWarningCodes.ANSWER_WHILE_PEER_ACTIVE,
            name = "ANSWER_WHILE_PEER_ACTIVE",
            message = "Answer received while call active",
            description = "An incoming call answer was received while a call is already active.",
            causes = listOf("Simultaneous answer from multiple devices", "Race condition in multi-device flow"),
            solutions = listOf("End the active call before answering a new one")
        ),
        TelnyxWarningCodes.DUPLICATE_INBOUND_ANSWER to TelnyxWarning(
            code = TelnyxWarningCodes.DUPLICATE_INBOUND_ANSWER,
            name = "DUPLICATE_INBOUND_ANSWER",
            message = "Duplicate inbound answer",
            description = "A duplicate answer was received for an already-answered call.",
            causes = listOf("Server retransmission", "Race condition"),
            solutions = listOf("No action needed — SDK ignores the duplicate")
        ),
        TelnyxWarningCodes.ICE_CANDIDATE_PAIR_CHANGED to TelnyxWarning(
            code = TelnyxWarningCodes.ICE_CANDIDATE_PAIR_CHANGED,
            name = "ICE_CANDIDATE_PAIR_CHANGED",
            message = "ICE candidate pair changed",
            description = "The active ICE candidate pair changed, which may briefly affect audio quality.",
            causes = listOf("Network path change", "Better candidate found", "Current pair degraded"),
            solutions = listOf("No action needed — SDK handles automatically")
        ),
        TelnyxWarningCodes.AUDIO_INPUT_DEVICE_CHANGE_SKIPPED to TelnyxWarning(
            code = TelnyxWarningCodes.AUDIO_INPUT_DEVICE_CHANGE_SKIPPED,
            name = "AUDIO_INPUT_DEVICE_CHANGE_SKIPPED",
            message = "Audio device change skipped",
            description = "An audio input device change was detected but could not be applied during an active call.",
            causes = listOf("Device changed mid-call", "Bluetooth reconnection"),
            solutions = listOf("Restart the call to use the new device")
        ),
        TelnyxWarningCodes.MULTIPLE_ACTIVE_CALLS_DETECTED to TelnyxWarning(
            code = TelnyxWarningCodes.MULTIPLE_ACTIVE_CALLS_DETECTED,
            name = "MULTIPLE_ACTIVE_CALLS_DETECTED",
            message = "Multiple active calls detected",
            description = "Multiple active calls were detected simultaneously, which may cause unexpected behavior.",
            causes = listOf("Simultaneous incoming calls", "Push delivery race condition"),
            solutions = listOf("End one call before starting another")
        ),
        TelnyxWarningCodes.SHARED_REMOTE_ELEMENT_OVERWRITE to TelnyxWarning(
            code = TelnyxWarningCodes.SHARED_REMOTE_ELEMENT_OVERWRITE,
            name = "SHARED_REMOTE_ELEMENT_OVERWRITE",
            message = "Remote element overwritten",
            description = "A shared remote media element was overwritten by a new call.",
            causes = listOf("New call reused existing media element", "Improper cleanup"),
            solutions = listOf("Ensure each call has its own media element")
        ),

        // ── Authentication warnings (340xx) ──────────────────────────────
        TelnyxWarningCodes.TOKEN_EXPIRING_SOON to TelnyxWarning(
            code = TelnyxWarningCodes.TOKEN_EXPIRING_SOON,
            name = "TOKEN_EXPIRING_SOON",
            message = "Token expiring soon",
            description = "The JWT token will expire soon. Reconnect with a fresh token to avoid disconnection.",
            causes = listOf("Token nearing expiration time"),
            solutions = listOf("Generate a new token", "Reconnect before the token expires")
        ),

        // ── Session / reconnection warnings (350xx) ────────────────────
        TelnyxWarningCodes.UNKNOWN_REATTACHED_SESSION to TelnyxWarning(
            code = TelnyxWarningCodes.UNKNOWN_REATTACHED_SESSION,
            name = "UNKNOWN_REATTACHED_SESSION",
            message = "Unknown reattached session",
            description = "The SDK reattached to a session but the server does not recognize it.",
            causes = listOf("Session expired on server", "Stale voice_sdk_id", "Server restart"),
            solutions = listOf("Start a new session")
        ),

        // ── Signaling health warnings (360xx) ─────────────────────────
        TelnyxWarningCodes.SIGNALING_RECOVERY_REQUIRED to TelnyxWarning(
            code = TelnyxWarningCodes.SIGNALING_RECOVERY_REQUIRED,
            name = "SIGNALING_RECOVERY_REQUIRED",
            message = "Signaling recovery required",
            description = "The signaling channel requires recovery. The SDK will attempt to reconnect.",
            causes = listOf("WebSocket connection dropped", "Gateway state changed"),
            solutions = listOf("SDK will reconnect automatically", "Check network if reconnection fails")
        ),
        TelnyxWarningCodes.MEDIA_RECOVERY_REQUIRED to TelnyxWarning(
            code = TelnyxWarningCodes.MEDIA_RECOVERY_REQUIRED,
            name = "MEDIA_RECOVERY_REQUIRED",
            message = "Media recovery required",
            description = "The media path requires recovery. The SDK will attempt ICE restart.",
            causes = listOf("ICE connectivity lost", "Network change"),
            solutions = listOf("SDK will attempt ICE restart", "Check network stability")
        ),
        TelnyxWarningCodes.RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT to TelnyxWarning(
            code = TelnyxWarningCodes.RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT,
            name = "RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT",
            message = "Reconnection failed",
            description = "Reconnection failed and auto-reconnect is disabled. Manual reconnection is required.",
            causes = listOf("Network unavailable", "Server unreachable", "Auto-reconnect disabled in config"),
            solutions = listOf("Check network connectivity", "Manually call connect() when network is available")
        )
    )

    /**
     * Look up a warning definition by code.
     *
     * @param code The numeric warning code
     * @return The [TelnyxWarning] definition, or null if not found
     */
    fun lookup(code: Int): TelnyxWarning? = registry[code]

    /**
     * Create a TelnyxWarning event from a registry definition, optionally
     * attaching call/session context.
     *
     * @param code The numeric warning code
     * @param callId Optional call identifier
     * @param sessionId Optional session identifier
     * @return A [TelnyxWarning] with context attached
     */
    fun create(
        code: Int,
        callId: UUID? = null,
        sessionId: String? = null
    ): TelnyxWarning {
        val base = registry[code] ?: return TelnyxWarning(
            code = code,
            name = "UNKNOWN_WARNING",
            message = "Unknown warning",
            description = "An unknown warning was emitted.",
            causes = emptyList(),
            solutions = emptyList(),
            callId = callId,
            sessionId = sessionId
        )
        return base.copy(callId = callId, sessionId = sessionId)
    }
}
