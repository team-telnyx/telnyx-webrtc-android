/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.Region
import com.telnyx.webrtc.sdk.utilities.TxLogger

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
 * @property fcmToken The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 * @property customLogger Optional custom logger implementation to handle SDK logs
 * @property autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials
 * @property debug whether or not to send client debug reports
 * @property reconnectionTimeout how long the app should try to reconnect to the socket server before giving up
 * @property region the region to use for the connection
 * @property fallbackOnRegionFailure whether or not connect to default region if the select region is not reachable
 * @property forceRelayCandidate whether to force TURN relay for peer connections. Note that this may cause issues with some networks that do not allow TURN connections. Enabling this may affect the quality of calls when devices are on the same local network, as all media will be relayed through TURN servers.
 */
data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val customLogger: TxLogger? = null,
    val autoReconnect: Boolean = false,
    val debug: Boolean = DEFAULT_DEBUG,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true,
    val forceRelayCandidate: Boolean = false
) : TelnyxConfig() {
    companion object {
        const val DEFAULT_DEBUG = false
    }
}

/**
 * Represents a SIP user for login - Token based
 *
 * @property sipToken The JWT token for the SIP user.
 * @property sipCallerIDName The user's chosen Caller ID Name
 * @property sipCallerIDNumber The user's Caller ID Number
 * @property fcmToken The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 * @property customLogger Optional custom logger implementation to handle SDK logs
 * @property autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with a valid token
 * @property debug whether or not to send client debug reports
 * @property reconnectionTimeout how long the app should try to reconnect to the socket server before giving up
 * @property region the region to use for the connection
 * @property fallbackOnRegionFailure whether or not connect to default region if the select region is not reachable
 * @property forceRelayCandidate whether to force TURN relay for peer connections. Note that this may cause issues with some networks that do not allow TURN connections. Enabling this may affect the quality of calls when devices are on the same local network, as all media will be relayed through TURN servers.
 */
data class TokenConfig(
    val sipToken: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val customLogger: TxLogger? = null,
    val autoReconnect: Boolean = true,
    val debug: Boolean = DEFAULT_DEBUG,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true,
    val forceRelayCandidate: Boolean = false
) : TelnyxConfig() {
    companion object {
        const val DEFAULT_DEBUG = false
    }
}
