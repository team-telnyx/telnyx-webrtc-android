/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Static registry of all structured warning definitions in the Telnyx Android SDK.
 *
 * This is a direct port of the JS SDK's warning system. Warnings are non-fatal
 * events that indicate degraded quality, potential issues, or recoverable states.
 *
 * ## Code ranges
 *
 * - 310xx — Network quality warnings (RTT, jitter, packet loss, MOS, audio levels)
 * - 320xx — Connection / data-flow warnings (bytes, recording)
 * - 330xx — Call connection warnings (ICE, peer connection, candidates)
 * - 340xx — Authentication warnings (token expiring)
 * - 350xx — Session / reconnection warnings
 * - 360xx — Signaling health warnings
 */
object TelnyxWarningRegistry {

    // ── Network quality warnings (310xx) ────────────────────────────────

    private val HIGH_RTT = TelnyxWarning(
        code = 31001,
        name = "HIGH_RTT",
        message = "High round-trip time detected",
        description = "The round-trip time for the connection exceeds the acceptable threshold, which may cause noticeable latency in audio communication.",
        causes = listOf(
            "Network congestion",
            "Long physical distance to server",
            "Unstable network connection",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Switch to a more stable network if available",
        ),
    )

    private val HIGH_JITTER = TelnyxWarning(
        code = 31002,
        name = "HIGH_JITTER",
        message = "High jitter detected",
        description = "Variation in packet arrival times exceeds the acceptable threshold, which may cause audio quality degradation.",
        causes = listOf(
            "Network congestion",
            "Unstable network connection",
            "Routing inconsistencies",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Switch to a more stable network if available",
        ),
    )

    private val HIGH_PACKET_LOSS = TelnyxWarning(
        code = 31003,
        name = "HIGH_PACKET_LOSS",
        message = "High packet loss detected",
        description = "The percentage of lost packets exceeds the acceptable threshold, which may cause audio cutting or gaps.",
        causes = listOf(
            "Network congestion",
            "Unstable Wi-Fi or cellular connection",
            "Firewall or NAT issues",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Switch to a more stable network if available",
        ),
    )

    private val LOW_MOS = TelnyxWarning(
        code = 31004,
        name = "LOW_MOS",
        message = "Low call quality score",
        description = "The Mean Opinion Score (MOS) for the call has dropped below the acceptable threshold, indicating poor audio quality.",
        causes = listOf(
            "High packet loss",
            "High jitter",
            "High RTT",
            "Codec mismatch",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Switch to a more stable network if available",
        ),
    )

    private val LOW_LOCAL_AUDIO = TelnyxWarning(
        code = 31005,
        name = "LOW_LOCAL_AUDIO",
        message = "Low local audio level",
        description = "The local audio input level is very low, which may make the caller difficult to hear for the remote party.",
        causes = listOf(
            "Microphone volume too low",
            "Microphone partially obstructed",
            "Wrong audio input device selected",
        ),
        solutions = listOf(
            "Check microphone positioning",
            "Increase microphone volume in system settings",
            "Verify the correct audio input device is selected",
        ),
    )

    private val LOW_INBOUND_AUDIO = TelnyxWarning(
        code = 31006,
        name = "LOW_INBOUND_AUDIO",
        message = "Low inbound audio level",
        description = "The remote party's audio level is very low, which may make them difficult to hear.",
        causes = listOf(
            "Remote party's microphone volume too low",
            "Remote party's microphone partially obstructed",
            "Network issues affecting audio quality",
        ),
        solutions = listOf(
            "Ask the remote party to check their microphone",
            "Increase speaker volume",
        ),
    )

    // ── Connection / data-flow warnings (320xx) ─────────────────────────

    private val LOW_BYTES_RECEIVED = TelnyxWarning(
        code = 32001,
        name = "LOW_BYTES_RECEIVED",
        message = "Low incoming data rate",
        description = "The number of bytes received from the remote party is very low, which may indicate audio is not flowing correctly.",
        causes = listOf(
            "Remote party has muted their microphone",
            "Network issues affecting media flow",
            "Media encryption or codec negotiation issues",
        ),
        solutions = listOf(
            "Check if the remote party has muted",
            "Check network connectivity",
        ),
    )

    private val LOW_BYTES_SENT = TelnyxWarning(
        code = 32002,
        name = "LOW_BYTES_SENT",
        message = "Low outgoing data rate",
        description = "The number of bytes sent to the remote party is very low, which may indicate audio is not flowing correctly.",
        causes = listOf(
            "Local microphone is muted",
            "Network issues affecting media flow",
            "Media encryption or codec negotiation issues",
        ),
        solutions = listOf(
            "Check if the microphone is muted",
            "Check network connectivity",
        ),
    )

    private val RECORDING_UNAVAILABLE = TelnyxWarning(
        code = 32003,
        name = "RECORDING_UNAVAILABLE",
        message = "Call recording is not available",
        description = "Call recording is not supported in the current environment or browser.",
        causes = listOf("Browser does not support MediaRecorder API", "Recording not enabled for this call"),
        solutions = listOf("Check browser compatibility", "Enable recording if supported"),
    )

    private val RECORDING_BUFFER_OVERFLOW = TelnyxWarning(
        code = 32004,
        name = "RECORDING_BUFFER_OVERFLOW",
        message = "Call recording buffer overflow",
        description = "The recording buffer overflowed and oldest packets were dropped to prevent memory exhaustion.",
        causes = listOf("Recording duration too long", "Insufficient memory", "Slow storage I/O"),
        solutions = listOf("Reduce recording duration", "Free up device memory"),
    )

    // ── Call connection warnings (330xx) ─────────────────────────────────

    private val ICE_CONNECTIVITY_LOST = TelnyxWarning(
        code = 33001,
        name = "ICE_CONNECTIVITY_LOST",
        message = "ICE connectivity lost",
        description = "The ICE connection between the local and remote peer has been lost. The SDK will attempt to recover via ICE restart or reconnection.",
        causes = listOf(
            "Network change (e.g. Wi-Fi to cellular)",
            "NAT timeout",
            "Firewall blocking UDP traffic",
        ),
        solutions = listOf(
            "SDK will attempt automatic recovery",
            "Check network connectivity",
            "If the call does not recover, hang up and retry",
        ),
    )

    private val ICE_GATHERING_TIMEOUT = TelnyxWarning(
        code = 33002,
        name = "ICE_GATHERING_TIMEOUT",
        message = "ICE gathering timed out",
        description = "ICE candidate gathering did not complete within the expected timeout. The call may proceed with partial candidates.",
        causes = listOf(
            "STUN server unreachable",
            "Network restrictions blocking ICE gathering",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Verify STUN server configuration",
        ),
    )

    private val ICE_GATHERING_EMPTY = TelnyxWarning(
        code = 33003,
        name = "ICE_GATHERING_EMPTY",
        message = "No ICE candidates gathered",
        description = "ICE candidate gathering completed but produced zero candidates. The call cannot proceed without candidates.",
        causes = listOf(
            "No network interface available",
            "All ICE servers unreachable",
            "Firewall blocking all ICE traffic",
        ),
        solutions = listOf(
            "Check network connectivity",
            "Verify ICE server configuration",
            "Try a different network",
        ),
    )

    private val PEER_CONNECTION_FAILED = TelnyxWarning(
        code = 33004,
        name = "PEER_CONNECTION_FAILED",
        message = "Peer connection failed",
        description = "The RTCPeerConnection has transitioned to a failed state. The SDK will attempt recovery via ICE restart.",
        causes = listOf(
            "ICE connectivity lost",
            "Network change",
            "Media negotiation failure",
        ),
        solutions = listOf(
            "SDK will attempt automatic ICE restart",
            "If the call does not recover, hang up and retry",
        ),
    )

    private val ONLY_HOST_ICE_CANDIDATES = TelnyxWarning(
        code = 33005,
        name = "ONLY_HOST_ICE_CANDIDATES",
        message = "Only local network candidates available",
        description = "ICE gathering produced only host (local network) candidates. No STUN or TURN candidates were gathered, which may prevent connectivity across NATs or firewalls.",
        causes = listOf("STUN/TURN servers unreachable", "Firewall blocking STUN/TURN traffic", "No relay servers configured"),
        solutions = listOf("Check STUN/TURN server configuration", "Verify firewall allows STUN/TURN traffic"),
    )

    private val ANSWER_WHILE_PEER_ACTIVE = TelnyxWarning(
        code = 33006,
        name = "ANSWER_WHILE_PEER_ACTIVE",
        message = "Call answer ignored — peer connection already active",
        description = "An incoming call answer was ignored because a peer connection is already active for this session.",
        causes = listOf("Duplicate answer from server", "Race condition in multi-call handling"),
        solutions = listOf("No action needed — SDK handles automatically"),
    )

    private val DUPLICATE_INBOUND_ANSWER = TelnyxWarning(
        code = 33007,
        name = "DUPLICATE_INBOUND_ANSWER",
        message = "Duplicate inbound call answer ignored",
        description = "Call answer ignored because another inbound call is already being answered on this session.",
        causes = listOf("Duplicate INVITE from server", "Race condition in multi-call handling"),
        solutions = listOf("No action needed — SDK handles automatically"),
    )

    private val ICE_CANDIDATE_PAIR_CHANGED = TelnyxWarning(
        code = 33008,
        name = "ICE_CANDIDATE_PAIR_CHANGED",
        message = "ICE candidate pair changed mid-call",
        description = "The selected ICE candidate pair changed during an active call. This may cause a brief audio interruption.",
        causes = listOf("Network change (e.g. Wi-Fi to cellular)", "Selected pair priority changed"),
        solutions = listOf("No action needed — SDK handles automatically"),
    )

    private val AUDIO_INPUT_DEVICE_CHANGE_SKIPPED = TelnyxWarning(
        code = 33009,
        name = "AUDIO_INPUT_DEVICE_CHANGE_SKIPPED",
        message = "Audio input device change skipped",
        description = "An audio input device change was skipped because the new device could not be resolved or was unavailable.",
        causes = listOf("Device disconnected during switch", "Invalid device ID"),
        solutions = listOf("Verify the audio device is connected", "Retry the device switch"),
    )

    private val MULTIPLE_ACTIVE_CALLS_DETECTED = TelnyxWarning(
        code = 33010,
        name = "MULTIPLE_ACTIVE_CALLS_DETECTED",
        message = "Multiple active calls detected",
        description = "Multiple active calls were detected in a single SDK session. This may cause unexpected behavior.",
        causes = listOf("Race condition in call setup", "Server-side duplicate INVITE"),
        solutions = listOf("Hang up the extra call", "Check call management logic"),
    )

    private val SHARED_REMOTE_ELEMENT_OVERWRITE = TelnyxWarning(
        code = 33011,
        name = "SHARED_REMOTE_ELEMENT_OVERWRITE",
        message = "Remote media element overwritten",
        description = "A shared remote media element was overwritten by another call's audio stream.",
        causes = listOf("Multiple calls sharing the same media element"),
        solutions = listOf("Use separate media elements per call"),
    )

    private val REMOTE_AUDIO_ELEMENT_UNRESOLVED = TelnyxWarning(
        code = 33012,
        name = "REMOTE_AUDIO_ELEMENT_UNRESOLVED",
        message = "Remote audio element could not be resolved",
        description = "The configured remote media element could not be resolved. Audio may not play for the current call.",
        causes = listOf("Media element was removed from the DOM", "Invalid media element reference"),
        solutions = listOf("Verify the media element is valid", "Reconfigure the audio output"),
    )

    // ── Authentication warnings (340xx) ─────────────────────────────────

    private val TOKEN_EXPIRING_SOON = TelnyxWarning(
        code = 34001,
        name = "TOKEN_EXPIRING_SOON",
        message = "Authentication token expiring soon",
        description = "The authentication token used for the current session will expire soon. The client should generate a new token and reconnect before expiration.",
        causes = listOf(
            "Token TTL approaching expiration",
        ),
        solutions = listOf(
            "Generate a new authentication token",
            "Reconnect with the new token before the current one expires",
        ),
    )

    // ── Session / reconnection warnings (350xx) ─────────────────────────

    private val UNKNOWN_REATTACHED_SESSION = TelnyxWarning(
        code = 35002,
        name = "UNKNOWN_REATTACHED_SESSION",
        message = "Unknown reattached session",
        description = "The server reattached a session that the SDK did not recognize. This may happen after a restart or cache clear.",
        causes = listOf(
            "Local session state was lost (app restart, cache clear)",
            "Server-side session from a previous installation",
        ),
        solutions = listOf(
            "No action needed — the SDK will terminate the unknown session",
        ),
    )

    // ── Signaling health warnings (360xx) ──────────────────────────────

    private val SIGNALING_RECOVERY_REQUIRED = TelnyxWarning(
        code = 36003,
        name = "SIGNALING_RECOVERY_REQUIRED",
        message = "Signaling recovery required",
        description = "The signaling connection requires recovery. The SDK will attempt to reconnect and reattach any active calls.",
        causes = listOf(
            "WebSocket connection dropped",
            "Gateway timeout",
            "Network interruption",
        ),
        solutions = listOf(
            "SDK will attempt automatic recovery",
            "If recovery fails, the SDK will emit a RECONNECTION_EXHAUSTED error",
        ),
    )

    private val MEDIA_RECOVERY_REQUIRED = TelnyxWarning(
        code = 36004,
        name = "MEDIA_RECOVERY_REQUIRED",
        message = "Media recovery required",
        description = "The media path requires recovery. The SDK will attempt an ICE restart to restore the media connection.",
        causes = listOf(
            "ICE connectivity lost",
            "Peer connection failed",
            "Network change",
        ),
        solutions = listOf(
            "SDK will attempt automatic ICE restart",
            "If recovery fails, the call may need to be terminated",
        ),
    )

    private val RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT = TelnyxWarning(
        code = 36005,
        name = "RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT",
        message = "Reconnection failed (auto-reconnect disabled)",
        description = "A reconnection attempt failed and autoReconnect is disabled. The client must manually reconnect.",
        causes = listOf(
            "autoReconnect is set to false",
            "Reconnection attempt failed",
        ),
        solutions = listOf(
            "Call client.disconnect() and client.connect() to manually retry",
            "Check network connectivity",
        ),
    )

    /**
     * Lookup table: warning code → [TelnyxWarning] definition.
     */
    private val registry: Map<Int, TelnyxWarning> = mapOf(
        31001 to HIGH_RTT,
        31002 to HIGH_JITTER,
        31003 to HIGH_PACKET_LOSS,
        31004 to LOW_MOS,
        31005 to LOW_LOCAL_AUDIO,
        31006 to LOW_INBOUND_AUDIO,
        32001 to LOW_BYTES_RECEIVED,
        32002 to LOW_BYTES_SENT,
        32003 to RECORDING_UNAVAILABLE,
        32004 to RECORDING_BUFFER_OVERFLOW,
        33001 to ICE_CONNECTIVITY_LOST,
        33002 to ICE_GATHERING_TIMEOUT,
        33003 to ICE_GATHERING_EMPTY,
        33004 to PEER_CONNECTION_FAILED,
        33005 to ONLY_HOST_ICE_CANDIDATES,
        33006 to ANSWER_WHILE_PEER_ACTIVE,
        33007 to DUPLICATE_INBOUND_ANSWER,
        33008 to ICE_CANDIDATE_PAIR_CHANGED,
        33009 to AUDIO_INPUT_DEVICE_CHANGE_SKIPPED,
        33010 to MULTIPLE_ACTIVE_CALLS_DETECTED,
        33011 to SHARED_REMOTE_ELEMENT_OVERWRITE,
        33012 to REMOTE_AUDIO_ELEMENT_UNRESOLVED,
        34001 to TOKEN_EXPIRING_SOON,
        35002 to UNKNOWN_REATTACHED_SESSION,
        36003 to SIGNALING_RECOVERY_REQUIRED,
        36004 to MEDIA_RECOVERY_REQUIRED,
        36005 to RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT,
    )

    /**
     * Returns the [TelnyxWarning] definition for the given [code], or null if not found.
     */
    fun get(code: Int): TelnyxWarning? = registry[code]

    /**
     * Returns all registered warning codes.
     */
    fun codes(): Set<Int> = registry.keys
}
