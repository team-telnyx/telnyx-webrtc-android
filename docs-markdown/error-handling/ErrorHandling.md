# Error Handling in Telnyx WebRTC Android SDK

This document describes the error handling mechanisms in the Telnyx WebRTC Android SDK, specifically focusing on when and why error events are triggered and how they are processed through the SDK.

## Error Handling Architecture

The Android SDK implements a structured approach to error handling through several key components:

1. **TxSocketListener Interface**: Defines the `onErrorReceived` method that is triggered when socket errors occur
2. **SocketResponse Class**: Provides a data structure for encapsulating error states with the `error()` factory method
3. **TelnyxClient Implementation**: Processes errors and exposes them through LiveData for application consumption

## Error Scenarios

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

### 3. Network Connectivity Issues

The SDK detects network connectivity problems and reports them as errors:

* When attempting to connect without an active network connection
* When network is lost during an active session
* These errors help applications handle offline scenarios gracefully

Example:
```kotlin
if (!isNetworkAvailable()) {
    socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
    return
}
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

## Consuming Errors in Your Application

The SDK exposes errors through the `socketResponseLiveData` LiveData object, which applications can observe to handle errors appropriately.

Example implementation:

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
                    attemptReconnection()
                }
                response.errorMessage?.contains("No Network Connection") == true -> {
                    // Handle network connectivity issues
                    showOfflineUI()
                }
                else -> {
                    // Handle other types of errors
                    showErrorToUser(response.errorMessage)
                }
            }
        }
        // Handle other socket statuses...
    }
})
```

## Error Handling Best Practices

When implementing error handling for the Telnyx WebRTC Android SDK:

1. **Always observe the socketResponseLiveData**: This is the primary channel for receiving error notifications
2. **Log errors for debugging purposes**: Capture error messages for troubleshooting
3. **Implement appropriate error recovery mechanisms**: Different errors may require different recovery strategies
4. **Display user-friendly error messages**: Translate technical error messages into user-friendly notifications
5. **Implement reconnection logic when appropriate**: For network or gateway issues, automatic reconnection may be appropriate

## Common Error Scenarios and Solutions

### Gateway Registration Failure
- **Cause**: Network connectivity issues or invalid credentials
- **Solution**: Check network connection and credential validity, then attempt reconnection

### WebSocket Connection Errors
- **Cause**: Network interruption or server issues
- **Solution**: Implement automatic reconnection with exponential backoff

### No Network Connection
- **Cause**: Device is offline or has poor connectivity
- **Solution**: Monitor network state changes and reconnect when network becomes available

## Additional Resources

- [Telnyx WebRTC Android SDK GitHub Repository](https://github.com/team-telnyx/telnyx-webrtc-android)
- [API Documentation](https://developers.telnyx.com/docs/v2/webrtc)