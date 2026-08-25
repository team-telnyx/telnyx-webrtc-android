/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * Structured SDK warning event.
 *
 * Warnings represent degraded conditions that may cause unstable
 * connections or bad call experience. Surfaced via [TelnyxClient.warningFlow].
 *
 * @param code Numeric warning code (e.g. 31001)
 * @param name Machine-readable name in UPPER_SNAKE_CASE
 * @param message Short human-readable message for UI alerts
 * @param description Full explanation of the warning
 * @param causes Possible root causes
 * @param solutions Suggested remediation steps
 * @param callId Call identifier when the warning is associated with a call
 * @param sessionId Current SDK session identifier
 */
data class TelnyxWarning(
    val code: Int,
    val name: String,
    val message: String,
    val description: String,
    val causes: List<String>,
    val solutions: List<String>,
    val callId: UUID? = null,
    val sessionId: String? = null
)
