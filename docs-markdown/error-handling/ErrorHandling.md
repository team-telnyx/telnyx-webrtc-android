This document describes the error handling mechanisms and call state event details in the Telnyx WebRTC Android SDK, specifically focusing on when and why error events are triggered, how call state changes are reported, and how they are processed through the SDK.

## Error Handling Architecture

The Android SDK implements a structured approach to error handling through several key components:

1. **TxSocketListener Interface**: Defines the `onErrorReceived` method that is triggered when socket errors occur. It also defines methods like `onByeReceived` which now provide more detailed information for call termination.
2. **SocketResponse Class**: Provides a data structure for encapsulating error states with the `error()` factory method, and for successful message responses.
3. **TelnyxClient Implementation**: Processes errors and exposes them through LiveData for application consumption. It also manages and exposes call state transitions.
4. **CallState Sealed Class**: Represents various states a call can be in, with some states now carrying detailed reasons for transitions (e.g., `DROPPED`, `RECONNECTING`, `DONE`).

## Error Scenarios and Informative Call State Transitions

### 1. Gateway Registration Status

The SDK monitors the gateway registration status and triggers errors in the following scenarios:

* When the gateway status is not "REGED" (registered) after an initial attempt and retry
* When the gateway status is "FAILED" after multiple retries
* Location: [TelnyxClient.kt](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/telnyx_rtc/src/main/java/com/telnyx/webrtc/sdk/TelnyxClient.kt)
* This ensures that the client is properly connected to the Telnyx network

Example:
```kotlin
when (gatewayState) {
    GatewayState.NOREG.state -> {
        invalidateGatewayResponseTimer()
        socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has timed out"))
    }
    
    GatewayState.FAILED.state -> {
        invalidateGatewayResponseTimer()
        socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has failed"))
    }
}
```

### 2. WebSocket Error Messages

The SDK handles error messages received through the WebSocket connection:

* When the server sends an error message via WebSocket
* Location: [TelnyxClient.kt - onErrorReceived method](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/telnyx_rtc/src/main/java/com/telnyx/webrtc/sdk/TelnyxClient.kt)
* These errors typically indicate issues with the connection or server-side problems

Example:
```kotlin
override fun onErrorReceived(jsonObject: JsonObject) {
    val errorMessage = jsonObject.get("error").asJsonObject.get("message").asString
    Logger.d(message = "onErrorReceived " + errorMessage)
    socketResponseLiveData.postValue(SocketResponse.error(errorMessage))
}
```

### 3. Network Connectivity Issues and Related Call States

The SDK detects network connectivity problems and reports them as errors or specific call states:

* **Error on Connect Attempt**: When attempting to connect without an active network connection
* **CallState.DROPPED**: When network is lost during an active session and reconnection is not possible or fails
* **CallState.RECONNECTING**: When the SDK attempts to reconnect a call after a network disruption (e.g., switching from Wi-Fi to LTE)

Example:
```kotlin
if (!ConnectivityHelper.isNetworkEnabled(context)) {
    socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
    return
}
```

### 4. Call Termination Details (`CallState.DONE` and `ByeResponse`)

When a call ends, the SDK provides detailed reasons for termination:

* **CallState.DONE**: This state now optionally includes a `CallTerminationReason` object
* **Bye Event (`onByeReceived`)**: The `TxSocketListener.onByeReceived` method now accepts a `JsonObject`

Example of consuming `CallState.DONE`:
```kotlin
// In your ViewModel or UI observer for Call.callStateFlow
aCall.callStateFlow.collect { state ->
    if (state is CallState.DONE) {
        val reason = state.reason
        if (reason != null) {
            // Display detailed termination reason to the user
            val message = "Call ended: ${reason.cause ?: "Unknown cause"}" +
                          (reason.sipCode?.let { " (SIP: $it ${reason.sipReason ?: ""})" } ?: "")
            Log.i("CallEnd", message)
            // Show popup or toast with this message
        } else {
            Log.i("CallEnd", "Call ended without a specific reason provided.")
        }
    }
}
```

Example of consuming the enriched `ByeResponse` from `socketResponseLiveData`:
```kotlin
// In your observer for TelnyxClient.socketResponseLiveData
telnyxClient.socketResponseLiveData.observe(this, Observer { response ->
    if (response.status == SocketStatus.MESSAGERECEIVED && response.data?.method == SocketMethod.BYE.methodName) {
        val byeResponse = response.data.result as? com.telnyx.webrtc.sdk.verto.receive.ByeResponse
        if (byeResponse != null) {
            val terminationMessage = "Remote party ended call (${byeResponse.callId}). " +
                                     "Reason: ${byeResponse.cause ?: "N/A"}" +
                                     (byeResponse.sipCode?.let { " (SIP: $it ${byeResponse.sipReason ?: ""})" } ?: "")
            Log.i("TelnyxSDK", terminationMessage)
            // Update UI accordingly
        }
    }
    // ... handle other statuses and methods
})
```

## SocketResponse.error Implementation

The `SocketResponse` class provides a standardized way to handle errors throughout the SDK:

```kotlin
fun <T> error(msg: String): SocketResponse<T> {
    return SocketResponse(SocketStatus.ERROR, null, msg)
}
```

This factory method creates a `SocketResponse` object with:
- `SocketStatus.ERROR` status
- `null` data (since there is no valid data during an error)
- An error message describing what went wrong

## Consuming Errors and Call Events in Your Application

The SDK exposes errors and call-related events through the `socketResponseLiveData` LiveData object and individual `Call` objects' `callStateFlow`. Applications should observe these to handle events appropriately.

Example implementation for general errors:
```kotlin
telnyxClient.socketResponseLiveData.observe(this, Observer { response ->
    when (response.status) {
        SocketStatus.ERROR -> {
            // Log the error
            Log.e("TelnyxSDK", "Error: ${response.errorMessage}")
            
            // Handle specific error types
            when {
                response.errorMessage?.contains("Gateway registration") == true -> {
                    // Handle gateway registration failure
                    // attemptReconnection()
                }
                response.errorMessage?.contains("No Network Connection") == true -> {
                    // Handle network connectivity issues
                    // showOfflineUI()
                }
                else -> {
                    // Handle other types of errors
                    // showErrorToUser(response.errorMessage)
                }
            }
        }
        // Handle other socket statuses...
    }
})
```

## Error Handling and Call State Best Practices

When implementing error handling and observing call states:

1. **Always observe `socketResponseLiveData`**: For general SDK errors and message-based events like incoming `bye`.
2. **Observe `call.callStateFlow` for each `Call` object**: For detailed state transitions of individual calls (e.g., `ACTIVE`, `DROPPED`, `RECONNECTING`, `DONE` with reasons).
3. **Log errors and state changes for debugging purposes**: Capture messages for troubleshooting.
4. **Implement appropriate recovery mechanisms**: Different errors or states may require different recovery strategies (e.g., reconnection for `DROPPED` or `RECONNECTING` states).
5. **Display user-friendly messages**: Translate technical errors or state reasons into clear notifications.
    * For `CallState.DONE(reason)`, use the `CallTerminationReason` fields (`cause`, `sipCode`, `sipReason`) to inform the user. The Telnyx support documentation can be helpful for mapping these to user-friendly messages.
6. **Implement reconnection logic when appropriate**: For network or gateway issues.

## SIP Response Codes and Common Causes

For a detailed understanding of SIP response codes and common call failure reasons, refer to the official Telnyx troubleshooting guide:
[Troubleshooting Call Completion](https://support.telnyx.com/en/articles/5025298-troubleshooting-call-completion)

This guide provides valuable insights into why calls might fail with specific SIP codes (e.g., 403 Forbidden, 404 Not Found, 503 Service Unavailable) and their corresponding causes (e.g., Invalid Caller ID, Unallocated Number). Use this information in conjunction with the `sipCode` and `sipReason` from `CallTerminationReason` to provide more accurate feedback to users.

## Common Error Scenarios and Solutions

### Gateway Registration Failure
- **Cause**: Network connectivity issues or invalid credentials
- **Solution**: Check network connection and credential validity, then attempt reconnection

### WebSocket Connection Errors
- **Cause**: Network interruption or server issues
- **Solution**: Implement automatic reconnection with exponential backoff

### No Network Connection / Call Dropped
- **Cause**: Device is offline or has poor connectivity. `CallState.DROPPED(CallNetworkChangeReason.NETWORK_LOST)` will be triggered.
- **Solution**: Monitor network state changes. Inform the user. Reconnect when network becomes available.

### Call Reconnecting
- **Cause**: Temporary network disruption, like a network switch (e.g., Wi-Fi to mobile data). `CallState.RECONNECTING(CallNetworkChangeReason.NETWORK_SWITCH)` will be triggered.
- **Solution**: Inform the user that the call is attempting to reconnect. The SDK handles the reconnection attempt.

## Error Constants Reference

The following table lists some error constants/messages that might be observed:

| ERROR MESSAGE                 | ERROR CODE (Example) | DESCRIPTION                                                                                                    |
|-------------------------------|----------------------|----------------------------------------------------------------------------------------------------------------|
| Token registration error      | -32000               | The token used for authentication was invalid.                                                                 |
| Credential registration error | -32001               | Either the username or password used for authentication was invalid.                                           |
| Codec error                   | -32002               | SDP handshake failed; no matching codecs and/or ICE Candidates.                                                |
| Gateway registration timeout  | -32003               | Gateway registration timed out.                                                                                |
| Gateway registration failed   | -32004               | Gateway registration has timed out multiple times and has now failed.                                          |
| Call not found                | N/A                  | An action on a call was attempted (e.g., hold, DTMF) but the call no longer exists.                            |
| *Various SIP Errors*          | *SIP Codes (e.g. 403, 404)* | Refer to `sipCode` and `sipReason` in `CallTerminationReason` and the [Telnyx Troubleshooting Guide](https://support.telnyx.com/en/articles/5025298-troubleshooting-call-completion). |

These error constants are defined in the SDK's error handling system and can be used to identify specific error conditions in your application code.

## Additional Resources

- [Telnyx WebRTC Android SDK GitHub Repository](https://github.com/team-telnyx/telnyx-webrtc-android)
- [API Documentation](https://developers.telnyx.com/docs/v2/webrtc)
