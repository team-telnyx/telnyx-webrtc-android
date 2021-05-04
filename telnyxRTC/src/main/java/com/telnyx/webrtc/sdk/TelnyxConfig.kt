/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import com.telnyx.webrtc.sdk.model.LogLevel

/**
 * Represents a SIP user for login
 *
 * This is a data class to handle the properties provided when logging
 * into the TelnyxClient with SIP details
 *
 * The data class is either Credential or Token based.
 *
 * @see CredentialConfig
 * @see TokenConfig
 */
sealed class TelnyxConfig

/**
 * Represents a SIP user for login - Credential based
 *
 * @property sipUser The SIP username of the user logging in
 * @property sipPassword The SIP password of the user logging in
 * @property sipCallerIDName The user's chosen Caller ID Name
 * @property sipCallerIDNumber The user's Caller ID Number
 * @property fcmDeviceId The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value of the audio file to use as a ringtone
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 */
data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Int?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE
    ) : TelnyxConfig()

/**
 * Represents a SIP user for login - Token based
 *
 * @property sipToken The JWT token for the SIP user.
 * @property sipCallerIDName The user's chosen Caller ID Name
 * @property sipCallerIDNumber The user's Caller ID Number
 * @property fcmDeviceId The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value of the audio file to use as a ringtone
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 */
data class TokenConfig(
    val sipToken: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Int?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE
    ) : TelnyxConfig()

