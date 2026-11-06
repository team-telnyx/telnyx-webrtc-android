/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Structured error model for the Telnyx Android SDK, aligned with the JS SDK's
 * SdkErrorDefinition. Every error emitted by the SDK carries a code, a human-readable
 * name, a short message, a detailed description, possible causes, recommended
 * solutions, and a fatal flag indicating whether the session or call is terminal.
 *
 * Consumers should listen to [com.telnyx.webrtc.sdk.TelnyxClient.errorFlow] to receive
 * structured errors instead of parsing raw SocketResponse messages.
 *
 * @property code Numeric error code (matches JS SDK registry).
 * @property name Machine-readable name (e.g. "LOGIN_FAILED").
 * @property message Short human-readable summary.
 * @property description Detailed explanation of the error.
 * @property causes List of possible causes (static, from registry).
 * @property solutions List of recommended actions (static, from registry).
 * @property fatal true = terminal (session/call dead, client should stop),
 *                false = SDK is handling recovery or failure is benign.
 */
data class TelnyxError(
    val code: Int,
    val name: String,
    val message: String,
    val description: String,
    val causes: List<String>,
    val solutions: List<String>,
    val fatal: Boolean,
)

/**
 * Structured warning model for the Telnyx Android SDK, aligned with the JS SDK's
 * warning system. Warnings are non-fatal events that indicate degraded quality,
 * potential issues, or recoverable states.
 *
 * Consumers should listen to [com.telnyx.webrtc.sdk.TelnyxClient.warningFlow] to receive
 * structured warnings.
 *
 * @property code Numeric warning code (matches JS SDK registry).
 * @property name Machine-readable name (e.g. "ICE_CONNECTIVITY_LOST").
 * @property message Short human-readable summary.
 * @property description Detailed explanation of the warning.
 * @property causes List of possible causes (static, from registry).
 * @property solutions List of recommended actions (static, from registry).
 */
data class TelnyxWarning(
    val code: Int,
    val name: String,
    val message: String,
    val description: String,
    val causes: List<String>,
    val solutions: List<String>,
)
