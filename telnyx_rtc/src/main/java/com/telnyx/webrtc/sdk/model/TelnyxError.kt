/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * Structured SDK error event.
 *
 * Surfaced via [TelnyxClient.errorFlow] and [TelnyxClient.fatalErrorFlow].
 * Per-entry runtime guarantee lives on [fatal]: `true` = terminal, `false` = SDK
 * handles or safe to ignore.
 *
 * @param code Numeric error code (e.g. 40001)
 * @param name Machine-readable name in UPPER_SNAKE_CASE
 * @param message Short human-readable message for UI alerts
 * @param description Full explanation of the error
 * @param causes Possible root causes
 * @param solutions Suggested remediation steps
 * @param fatal Whether the situation is terminal — the operation/call/session is dead
 * @param callId Call identifier when the error is associated with a call
 * @param sessionId Current SDK session identifier
 */
data class TelnyxError(
    val code: Int,
    val name: String,
    val message: String,
    val description: String,
    val causes: List<String>,
    val solutions: List<String>,
    val fatal: Boolean,
    val callId: UUID? = null,
    val sessionId: String? = null
)
