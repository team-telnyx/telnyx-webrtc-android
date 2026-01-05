# Anonymous Connection for AI Agents

## Overview

The `connectAnonymously` method allows you to connect to AI assistants without traditional authentication credentials. This is the first step in establishing communication with a Telnyx AI Agent.

## Method Signature

```kotlin
fun connectAnonymously(
    providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
    targetId: String,
    targetType: String = "ai_assistant",
    targetVersionId: String? = null,
    userVariables: Map<String, Any>? = null,
    reconnection: Boolean = false,
    logLevel: LogLevel = LogLevel.NONE,
)
```

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `providedServerConfig` | TxServerConfiguration | No | TxServerConfiguration() | Server configuration for connection |
| `targetId` | String | Yes | - | The ID of your AI assistant |
| `targetType` | String | No | "ai_assistant" | The type of target |
| `targetVersionId` | String? | No | null | Optional version ID of the target. If not provided, uses latest version |
| `userVariables` | `Map<String, Any>?` | No | null | Optional user variables to include |
| `reconnection` | Boolean | No | false | Whether this is a reconnection attempt |
| `logLevel` | LogLevel | No | LogLevel.NONE | Logging level configuration |

## Usage Example

```kotlin
try {
    telnyxClient.connectAnonymously(
        targetId = "your_assistant_id",
        // targetType = "ai_assistant", // This is the default value
        // targetVersionId = "your_assistant_version_id", // Optional
        // userVariables = mapOf("user_id" to "12345"), // Optional user variables
        // logLevel = LogLevel.NONE // Optional log level configuration
    )
    // You are now connected and can make a call to the AI Assistant.
} catch (e: Exception) {
    // Handle connection error
    Log.e("TelnyxClient", "Connection failed: ${e.message}")
}
```

## Advanced Usage

### With User Variables

```kotlin
telnyxClient.connectAnonymously(
    targetId = "your_assistant_id",
    userVariables = mapOf(
        "user_id" to "12345",
        "session_context" to "support_chat",
        "language" to "en-US"
    )
)
```

### With Version Control

```kotlin
telnyxClient.connectAnonymously(
    targetId = "your_assistant_id",
    targetVersionId = "v1.2.0" // Use specific version
)
```

### With Custom Server Configuration

```kotlin
telnyxClient.connectAnonymously(
    providedServerConfig = TxServerConfiguration(
        host = "your-custom-host",
        port = 443
    ),
    targetId = "your_assistant_id"
)
```

## Important Notes

- **Call Routing**: After a successful anonymous connection, any subsequent call, regardless of the destination, will be directed to the specified AI Assistant
- **Session Lock**: The session becomes locked to the AI assistant until disconnection
- **Version Control**: If `targetVersionId` is not provided, the SDK will use the latest available version
- **Error Handling**: Monitor socket responses for authentication errors
- **Server Configuration**: Custom server configuration can be provided through the `providedServerConfig` parameter

## Socket Response Handling

Listen for connection responses using the socket response flow:

```kotlin
// Using SharedFlow (Recommended)
lifecycleScope.launch {
    telnyxClient.socketResponseFlow.collect { response ->
        when (response.status) {
            SocketStatus.MESSAGERECEIVED -> {
                response.data?.let { data ->
                    when (data.method) {
                        SocketMethod.LOGIN.methodName -> {
                            // Handle successful anonymous connection
                            Log.i("TelnyxClient", "Anonymous connection successful")
                        }
                    }
                }
            }
            SocketStatus.ERROR -> {
                // Handle connection errors
                Log.e("TelnyxClient", "Connection error: ${response.errorMessage}")
            }
        }
    }
}
```

## Error Handling

Common errors you might encounter:

```kotlin
lifecycleScope.launch {
    telnyxClient.socketResponseFlow.collect { response ->
        if (response.status == SocketStatus.ERROR) {
            when {
                response.errorMessage?.contains("authentication") == true -> {
                    // Handle authentication error
                    Log.e("TelnyxClient", "Invalid assistant ID or authentication failed")
                }
                response.errorMessage?.contains("network") == true -> {
                    // Handle network error
                    Log.e("TelnyxClient", "Network connection failed")
                }
                else -> {
                    // Handle other errors
                    Log.e("TelnyxClient", "Unexpected error: ${response.errorMessage}")
                }
            }
        }
    }
}
```

## Next Steps

After successful anonymous connection:
1. [Start a conversation](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/starting-conversations) using `newInvite()`
2. [Set up transcript updates](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/transcript-updates) to receive real-time conversation data
3. [Send text messages](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/text-messaging) during active calls