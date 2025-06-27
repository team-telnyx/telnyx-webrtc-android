## Telnyx Config

This is a sealed class to handle the properties provided when logging into the TelnyxClient with SIP details. The sealed class is either Credential or Token based.
```kotlin
sealed class TelnyxConfig
```

### Credential Config

Represents a SIP user for login - Credential based

**sipUser** - The SIP username of the user logging in

**sipPassword** - The SIP password of the user logging in

**sipCallerIDName** - The user's chosen Caller ID Name (optional)

**sipCallerIDNumber** - The user's Caller ID Number (optional)

**fcmToken** - The user's Firebase Cloud Messaging device ID (optional)

**ringtone** - The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri (optional)

**ringBackTone** - The integer raw value of the audio file to use as a ringback tone (optional)

**logLevel** - The log level that the SDK should use. Default value is `LogLevel.NONE`

**customLogger** - Optional custom logger implementation to handle SDK logs

**autoReconnect** - Whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials. Default is `false`

**debug** - Whether or not to send client debug reports. Default is `false`

**reconnectionTimeout** - How long the app should try to reconnect to the socket server before giving up (in milliseconds). Default is `60000`

**region** - The region to use for the connection. Default is `Region.AUTO`. See [Available Regions](#available-regions) for options

**fallbackOnRegionFailure** - Whether or not to connect to default region if the selected region is not reachable. Default is `true`

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
    val customLogger: TxLogger? = null,
    val autoReconnect: Boolean = false,
    val debug: Boolean = false,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true
) : TelnyxConfig()
```

### Token Config

Represents a SIP user for login - Token based

**sipToken** - The JWT token for the SIP user

**sipCallerIDName** - The user's chosen Caller ID Name (optional)

**sipCallerIDNumber** - The user's Caller ID Number (optional)

**fcmToken** - The user's Firebase Cloud Messaging device ID (optional)

**ringtone** - The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri (optional)

**ringBackTone** - The integer raw value of the audio file to use as a ringback tone (optional)

**logLevel** - The log level that the SDK should use. Default value is `LogLevel.NONE`

**customLogger** - Optional custom logger implementation to handle SDK logs

**autoReconnect** - Whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid token. Default is `true`

**debug** - Whether or not to send client debug reports. Default is `false`

**reconnectionTimeout** - How long the app should try to reconnect to the socket server before giving up (in milliseconds). Default is `60000`

**region** - The region to use for the connection. Default is `Region.AUTO`. See [Available Regions](#available-regions) for options

**fallbackOnRegionFailure** - Whether or not to connect to default region if the selected region is not reachable. Default is `true`

```kotlin
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
    val debug: Boolean = false,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true
) : TelnyxConfig()
```

## Available Regions

The `region` parameter accepts a `Region` enum value that determines which Telnyx data center region to connect to. This can help optimize connection quality and latency based on your geographic location.

### Region Options

**Region.AUTO** - Automatically selects the best region based on network conditions (default)

**Region.EU** - European region for users in Europe

**Region.US_CENTRAL** - US Central region for users in central United States

**Region.US_EAST** - US East region for users in eastern United States

**Region.US_WEST** - US West region for users in western United States

**Region.CA_CENTRAL** - Canada Central region for users in central Canada

**Region.APAC** - Asia-Pacific region for users in Asia-Pacific areas

### Usage Example

```kotlin
// Using a specific region
val credentialConfig = CredentialConfig(
    sipUser = "your_sip_user",
    sipPassword = "your_sip_password",
    sipCallerIDName = "Your Name",
    sipCallerIDNumber = "1234567890",
    region = Region.US_EAST,
    fallbackOnRegionFailure = true
)

// Using automatic region selection (default)
val tokenConfig = TokenConfig(
    sipToken = "your_jwt_token",
    sipCallerIDName = "Your Name",
    sipCallerIDNumber = "1234567890",
    region = Region.AUTO
)
```

### Region Fallback

When `fallbackOnRegionFailure` is set to `true` (default), the SDK will automatically fall back to the default region if the selected region is unreachable. This ensures better connection reliability while still attempting to use your preferred region first.