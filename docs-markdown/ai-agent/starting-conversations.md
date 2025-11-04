# Starting Conversations with AI Assistants

## Overview

After a successful `anonymousLogin`, you can initiate calls to your AI Assistant using the standard `newInvite` method. The session is locked to the AI Assistant, so the destination parameter is ignored.

## Method Usage

```kotlin
telnyxClient.newInvite(
    callerName: String, // Optional display name, not required to make call but is useful for log referencing
    callerNumber: String, // Optional caller number, not required but useful for log referencing
    destinationNumber: String, // Optional destination number, ignored after anonymous login. All calls will be routed to the AI assistant
    clientState: String, // Custom state information for your application
    customHeaders: Map<String, String> = emptyMap(), // Optional SIP headers to pass context to the AI assistant in the form of dynamic variables
)
```

## Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `callerName` | String | Your display name (passed to AI assistant) |
| `callerNumber` | String | Your phone number (passed to AI assistant) |
| `destinationNumber` | String | Ignored after anonymous login - can be empty string |
| `clientState` | String | Custom state information for your application |
| `customHeaders` | Map<String, String> | Optional SIP headers to pass context to the AI assistant (mapped to [dynamic variables](https://developers.telnyx.com/docs/inference/ai-assistants/dynamic-variables)) |

Note that you can also provide `customHeaders` in the `newInvite` method. These headers need to start with the `X-` prefix and will be mapped to [dynamic variables](https://developers.telnyx.com/docs/inference/ai-assistants/dynamic-variables) in the AI assistant (e.g., `X-Account-Number` becomes `{{account_number}}`). Hyphens in header names are converted to underscores in variable names.

## Usage Example

```kotlin
// After a successful anonymousLogin...

telnyxClient.call.newInvite(
    callerName = "John Doe",
    callerNumber = "+1234567890",
    destinationNumber = "", // Destination is ignored, can be empty
    clientState = "ai_conversation_session",
    customHeaders = mapOf(
        "X-Session-Context" to "support_request",
        "X-User-Tier" to "premium"),
    )
```

## Complete Flow Example

```kotlin
class AIAssistantManager(private val telnyxClient: TelnyxClient) {
    
    fun startAIConversation(assistantId: String) {
        // Step 1: Anonymous login
        telnyxClient.anonymousLogin(
            targetId = assistantId,
        )
        
        // Step 2: Listen for login success, then start call
        lifecycleScope.launch {
            telnyxClient.socketResponseFlow.collect { response ->
                when (response.status) {
                    SocketStatus.MESSAGERECEIVED -> {
                        response.data?.let { data ->
                            when (data.method) {
                                SocketMethod.LOGIN.methodName -> {
                                    // Login successful, start the call
                                    startCall()
                                }
                                SocketMethod.ANSWER.methodName -> {
                                    // AI Assistant answered automatically
                                    Log.i("AI", "Connected to AI Assistant")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    private fun startCall() {
        telnyxClient.call.newInvite(
            callerName = "Customer", // 
            callerNumber = "+1234567890", 
            destinationNumber = "", // Ignored
            clientState = "ai_session",
            customHeaders = mapOf(
                "X-Session-Context" to "customer_support",
                "X-User-Tier" to "premium")
        )
    }
}
```

## Important Notes

- **Automatic Answer**: AI assistants automatically answer calls - no manual answer required
- **Destination Ignored**: The `destinationNumber` parameter is ignored after anonymous login
- **Call Routing**: All calls are routed to the AI assistant specified during login
- **Standard Controls**: Use existing call management methods (mute, hold, end call)
- **Custom Headers**: You can pass custom SIP headers to provide context to the AI assistant. They will be mapped to [dynamic variables](https://developers.telnyx.com/docs/inference/ai-assistants/dynamic-variables) in the portal. Hyphens in header names are converted to underscores in variable names, e.g. `X-Session-Context` header maps to `{{session_context}}` variable.


## Call State Management

Monitor call states as you would with regular calls:

```kotlin
lifecycleScope.launch {
    telnyxClient.socketResponseFlow.collect { response ->
        when (response.status) {
            SocketStatus.MESSAGERECEIVED -> {
                response.data?.let { data ->
                    when (data.method) {
                        SocketMethod.INVITE.methodName -> {
                            // Call invitation sent
                            Log.i("AI", "Calling AI Assistant...")
                        }
                        SocketMethod.RINGING.methodName -> {
                            // AI Assistant is "ringing" (brief moment)
                            Log.i("AI", "AI Assistant ringing...")
                        }
                        SocketMethod.ANSWER.methodName -> {
                            // AI Assistant answered
                            Log.i("AI", "Connected to AI Assistant")
                            // Start listening for transcripts
                            setupTranscriptListener()
                        }
                        SocketMethod.BYE.methodName -> {
                            // Call ended
                            Log.i("AI", "AI conversation ended")
                        }
                    }
                }
            }
        }
    }
}
```

## Error Handling

Handle call-related errors:

```kotlin
lifecycleScope.launch {
    telnyxClient.socketResponseFlow.collect { response ->
        if (response.status == SocketStatus.ERROR) {
            when {
                response.errorMessage?.contains("invite") == true -> {
                    Log.e("AI", "Failed to start conversation with AI Assistant")
                }
                response.errorMessage?.contains("session") == true -> {
                    Log.e("AI", "Session expired, need to login again")
                }
                else -> {
                    Log.e("AI", "Call error: ${response.errorMessage}")
                }
            }
        }
    }
}
```

## Call Management

Once connected, use standard call management methods:

```kotlin
// Get the active call
val activeCall = telnyxClient.calls.values.firstOrNull()

activeCall?.let { call ->
    // Mute/unmute
    call.onMuteUnmutePressed()
    
    // Hold/unhold  
    call.onHoldUnholdPressed(call.callId)
    
    // End call
    call.endCall(call.callId)
}
```

## Next Steps

After starting a conversation:
1. [Set up transcript updates](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/transcript-updates) to receive real-time conversation data
2. [Send text messages](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/text-messaging) during the active call
3. Use standard call controls for mute, hold, and end call operations