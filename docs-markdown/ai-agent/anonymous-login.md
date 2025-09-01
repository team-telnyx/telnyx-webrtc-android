# Anonymous Login for AI Agents

## Overview

The `anonymousLogin` method allows you to connect to AI assistants without traditional authentication credentials. This is the first step in establishing communication with a Telnyx AI Agent.

## Method Signature

```kotlin
fun anonymousLogin(
    targetId: String,
    targetType: String = "ai_assistant",
    targetVersionId: String? = null,
    userVariables: Map<String, Any>? = null,
    reconnection: Boolean = false,
)
```

## Parameters

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `targetId` | String | Yes | - | The ID of your AI assistant |
| `targetType` | String | No | "ai_assistant" | The type of target |
| `targetVersionId` | String? | No | null | Optional version ID of the target. If not provided, uses latest version |
| `userVariables` | Map<String, Any>? | No | null | Optional user variables to include |
| `reconnection` | Boolean | No | false | Whether this is a reconnection attempt |

## Usage Example

```kotlin
try {
    telnyxClient.anonymousLogin(
        targetId = "your_assistant_id",
        // targetType = "ai_assistant", // This is the default value
        // targetVersionId = "your_assistant_version_id" // Optional
    )
    // You are now connected and can make a call to the AI Assistant.
} catch (e: Exception) {
    // Handle login error
    Log.e("TelnyxClient", "Login failed: ${e.message}")
}
```

## Advanced Usage

### With User Variables

```kotlin
telnyxClient.anonymousLogin(
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
telnyxClient.anonymousLogin(
    targetId = "your_assistant_id",
    targetVersionId = "v1.2.0" // Use specific version
)
```

## Important Notes

- **Call Routing**: After a successful `anonymousLogin`, any subsequent call, regardless of the destination, will be directed to the specified AI Assistant
- **Session Lock**: The session becomes locked to the AI assistant until disconnection
- **Version Control**: If `targetVersionId` is not provided, the SDK will use the latest available version
- **Error Handling**: Monitor socket responses for authentication errors

## Socket Response Handling

Listen for login responses using the socket response flow:

```kotlin
// Using SharedFlow (Recommended)
lifecycleScope.launch {
    telnyxClient.socketResponseFlow.collect { response ->
        when (response.status) {
            SocketStatus.MESSAGERECEIVED -> {
                response.data?.let { data ->
                    when (data.method) {
                        SocketMethod.LOGIN.methodName -> {
                            // Handle successful anonymous login
                            Log.i("TelnyxClient", "Anonymous login successful")
                        }
                    }
                }
            }
            SocketStatus.ERROR -> {
                // Handle login errors
                Log.e("TelnyxClient", "Login error: ${response.errorMessage}")
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

After successful anonymous login:
1. [Start a conversation](starting-conversations.md) using `newInvite()`
2. [Set up transcript updates](transcript-updates.md) to receive real-time conversation data
3. [Send text messages](text-messaging.md) during active calls