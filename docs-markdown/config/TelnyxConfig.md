## Telnyx Config

This is a sealed class to handle the properties provided when logging into the TelnyxClient with SIP details. The sealed class is either Credential or Token based.
```kotlin
sealed class TelnyxConfig
```

### Credential Config

Represents a SIP user for login - Credential based
 * sipUser The SIP username of the user logging in
 * sipPassword The SIP password of the user logging in
 * sipCallerIDName The user's chosen Caller ID Name
 * sipCallerIDNumber The user's Caller ID Number
 * fcmToken The user's Firebase Cloud Messaging device ID
 * ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * ringBackTone The integer raw value of the audio file to use as a ringback tone
 * logLevel The log level that the SDK should use - default value is none.
 * autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials

```kotlin
data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val autoReconnect: Boolean = false
) : TelnyxConfig()
```

### Token Config

Represents a SIP user for login - Token based
 * sipToken The JWT token for the SIP user.
 * sipCallerIDName The user's chosen Caller ID Name
 * sipCallerIDNumber The user's Caller ID Number
 * fcmToken The user's Firebase Cloud Messaging device ID
 * ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * ringBackTone The integer raw value of the audio file to use as a ringback tone
 * logLevel The log level that the SDK should use - default value is none.
 * autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with a valid token

```kotlin
data class TokenConfig(
    val sipToken: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val autoReconnect: Boolean = true,
) : TelnyxConfig()
```