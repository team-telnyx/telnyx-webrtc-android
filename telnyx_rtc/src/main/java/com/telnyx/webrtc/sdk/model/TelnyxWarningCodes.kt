/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Named constants for SDK warning codes.
 *
 * Code ranges:
 * - 310xx — Network quality warnings
 * - 320xx — Connection / data-flow warnings
 * - 330xx — Call connection warnings
 * - 340xx — Authentication warnings
 * - 350xx — Session / reconnection warnings
 * - 360xx — Signaling health warnings
 */
object TelnyxWarningCodes {
    // ── Network quality warnings (310xx) ──────────────────────────────
    const val HIGH_RTT = 31001
    const val HIGH_JITTER = 31002
    const val HIGH_PACKET_LOSS = 31003
    const val LOW_MOS = 31004
    const val LOW_LOCAL_AUDIO = 31005
    const val LOW_INBOUND_AUDIO = 31006

    // ── Connection / data-flow warnings (320xx) ───────────────────────
    const val LOW_BYTES_RECEIVED = 32001
    const val LOW_BYTES_SENT = 32002
    const val RECORDING_UNAVAILABLE = 32003
    const val RECORDING_BUFFER_OVERFLOW = 32004

    // ── Call connection warnings (330xx) ──────────────────────────────
    const val ICE_CONNECTIVITY_LOST = 33001
    const val ICE_GATHERING_TIMEOUT = 33002
    const val ICE_GATHERING_EMPTY = 33003
    const val PEER_CONNECTION_FAILED = 33004
    const val ONLY_HOST_ICE_CANDIDATES = 33005
    const val ANSWER_WHILE_PEER_ACTIVE = 33006
    const val DUPLICATE_INBOUND_ANSWER = 33007
    const val ICE_CANDIDATE_PAIR_CHANGED = 33008
    const val AUDIO_INPUT_DEVICE_CHANGE_SKIPPED = 33009
    const val MULTIPLE_ACTIVE_CALLS_DETECTED = 33010
    const val SHARED_REMOTE_ELEMENT_OVERWRITE = 33011

    // ── Authentication warnings (340xx) ───────────────────────────────
    const val TOKEN_EXPIRING_SOON = 34001

    // ── Session / reconnection warnings (350xx) ─────────────────────
    const val UNKNOWN_REATTACHED_SESSION = 35002

    // ── Signaling health warnings (360xx) ────────────────────────────
    const val SIGNALING_RECOVERY_REQUIRED = 36003
    const val MEDIA_RECOVERY_REQUIRED = 36004
    const val RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT = 36005
}
