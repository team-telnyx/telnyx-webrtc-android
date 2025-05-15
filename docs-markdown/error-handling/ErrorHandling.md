This document describes the error handling mechanisms and call state event details in the Telnyx WebRTC Android SDK. It is divided into a **Reference** section detailing possible errors and states, and a **Guide** section on how to consume and manage them.

## Error & Call State Reference

This section provides a reference for the various error conditions and call states you might encounter when using the SDK.

### 1. Socket-Level Errors (via `SocketResponse`)

These errors are typically reported through the `TelnyxClient.socketResponseLiveData` when `SocketResponse.status` is `SocketStatus.ERROR`.

*   **Gateway Registration Issues**:
    *   `"Gateway registration has timed out"`: Triggered if the gateway status is not "REGED" (registered) after an initial attempt and retry (e.g., `GatewayState.NOREG`).
    *   `"Gateway registration has failed"`: Triggered if the gateway status is "FAILED" after multiple retries.
*   **WebSocket Error Messages**:
    *   Custom error messages from the server sent via WebSocket (e.g., authentication failures, server-side issues). The `errorMessage` field in `SocketResponse` will contain the server-provided message.
*   **No Network Connection (on initial connect)**:
    *   `"No Network Connection"`: Triggered if an attempt to connect to the Telnyx platform is made when the device has no active network connection.

### 2. Call-Specific States & Reasons (via `Call.callStateFlow`)

Individual `Call` objects emit their state changes through `callStateFlow`. Several states now include detailed reasons:

*   **`CallState.DROPPED(reason: CallNetworkChangeReason)`**:
    *   Indicates a call was dropped, usually due to network problems.
    *   `reason` (of type `CallNetworkChangeReason`) provides context:
        *   `CallNetworkChangeReason.NETWORK_LOST`: Network connectivity was completely lost.
        *   `CallNetworkChangeReason.NETWORK_SWITCH`: A network switch occurred (e.g., Wi-Fi to Cellular), and while reconnection might be attempted, this state can be hit if it ultimately fails in that context, or if it's a direct drop without a reconnect attempt.
*   **`CallState.RECONNECTING(reason: CallNetworkChangeReason)`**:
    *   The SDK is attempting to reconnect a call after a network disruption.
    *   `reason` (of type `CallNetworkChangeReason`) provides context:
        *   `CallNetworkChangeReason.NETWORK_SWITCH`: Typically seen when the SDK tries to recover a call after a network handover.
*   **`CallState.DONE(reason: CallTerminationReason?)`**:
    *   The call has ended. The optional `reason` parameter (of type `CallTerminationReason`) provides details about *why* the call terminated.
    *   `CallTerminationReason` fields:
        *   `cause: String?`: A high-level cause string (e.g., "CALL_REJECTED", "USER_BUSY", "NORMAL_CLEARING").
        *   `causeCode: Int?`: A numerical code associated with the cause (e.g., 21 for CALL_REJECTED, 17 for USER_BUSY, 16 for NORMAL_CLEARING).
        *   `sipCode: Int?`: The SIP response code, if applicable (e.g., 403, 486, 404).
        *   `sipReason: String?`: The SIP reason phrase, if applicable (e.g., "Forbidden", "Busy Here", "Not Found").
*   **`CallState.ERROR`**:
    *   A general error occurred related to this specific call (e.g., failure to create offer/answer, media negotiation issues).

### 3. Enriched `ByeResponse` Details (via `socketResponseLiveData`)

When a call is terminated by the remote party, a `BYE` message is received. The `TxSocketListener.onByeReceived(jsonObject: JsonObject)` method is triggered, and `TelnyxClient` processes this.

*   The `com.telnyx.webrtc.sdk.verto.receive.ByeResponse` object, which is delivered as the `result` within `ReceivedMessageBody` (when `method` is `SocketMethod.BYE.methodName`) via `socketResponseLiveData`, is now enriched.
*   It contains the same detailed termination fields as `CallTerminationReason`: `callId`, `cause`, `causeCode`, `sipCode`, and `sipReason`.

### 4. Error/Cause Code Reference Table

This table provides common causes, codes, and potential SIP responses. For a comprehensive list of SIP codes and detailed troubleshooting, always refer to the [Telnyx Troubleshooting Guide for Call Completion](https://support.telnyx.com/en/articles/5025298-troubleshooting-call-completion).

| Category                      | `cause` String (Example) | `causeCode` (Example) | `sipCode` (Example) | `sipReason` (Example)         | Description / Common Scenario                                                               |
|-------------------------------|--------------------------|-----------------------|---------------------|-------------------------------|---------------------------------------------------------------------------------------------|
| **General Call Clearing**     | `NORMAL_CLEARING`        | 16                    | N/A                 | N/A                           | Call ended normally by one of the parties.                                                    |
|                               | `USER_BUSY`              | 17                    | 486                 | "Busy Here"                   | The called party is busy.                                                                     |
|                               | `CALL_REJECTED`          | 21                    | 403                 | "Forbidden"                   | Call was rejected (e.g., invalid caller ID, destination not whitelisted, authentication failure). |
|                               | `UNALLOCATED_NUMBER`     | 1                     | 404                 | "Not Found" / "Invalid Number" | The dialed number does not exist or is not assigned.                                          |
|                               | `NO_ANSWER`              | 19                    | 480                 | "Temporarily Unavailable"     | Callee did not answer.                                                                        |
|                               | `INCOMPATIBLE_DESTINATION`| 88                   | 488/606             | "Not Acceptable Here"       | Media negotiation failure (codec mismatch, SRTP issues).                                     |
|                               | `RECOVERY_ON_TIMER_EXPIRE`| 102                  | N/A (often 408)     | "Request Timeout"             | A necessary response was not received in time (e.g., INVITE timeout).                         |
| **Gateway/Network Issues**    | N/A                      | N/A                   | N/A                 | N/A                           | Refer to `CallState.DROPPED` or `CallState.RECONNECTING` with their `CallNetworkChangeReason`. |
| **SDK Internal Errors**       | `AnswerError`            | N/A                   | N/A                 | "No SDP in answer response"   | SDK specific error during call setup                                                |
|                               | `MediaError`             | N/A                   | N/A                 | "No SDP in media response"    | SDK specific error during media processing                                      |
| **Registration Errors**       | N/A                      | -32000                | N/A                 | N/A                           | Token registration error                                           |
|                               | N/A                      | -32001                | N/A                 | N/A                           | Credential registration error                                         |
|                               | N/A                      | -32003                | N/A                 | N/A                           | Gateway registration timeout                                              |
|                               | N/A                      | -32004                | N/A                 | N/A                           | Gateway registration failed                                              |

*Note: `cause`, `causeCode`, `sipCode`, and `sipReason` may not all be present for every `DONE` state. Presence depends on how the call was terminated.*

More SIP codes and their meanings can be found in the [Telnyx Troubleshooting Guide for Call Completion](https://support.telnyx.com/en/articles/4409457-telnyx-sip-response-codes).

## Guide: Consuming Errors and Call Events

This section explains how to effectively use the error and state information provided by the SDK.

### Observing `socketResponseLiveData` (for General SDK Events & Errors)

`TelnyxClient.socketResponseLiveData` is the primary channel for general SDK events, including connection status, errors, and messages like incoming `BYE`.

```kotlin
// In your Activity or ViewModel
telnyxClient.socketResponseLiveData.observe(this, Observer { response ->
    when (response.status) {
        SocketStatus.ERROR -> {
            Log.e("TelnyxSDK", "General SDK Error: ${response.errorMessage}")
            // Handle gateway registration issues, WebSocket errors, no network on connect, etc.
            // Example: if (response.errorMessage?.contains("Gateway registration") == true) { ... }
        }
        SocketStatus.MESSAGERECEIVED -> {
            response.data?.let { receivedMessageBody ->
                if (receivedMessageBody.method == SocketMethod.BYE.methodName) {
                    val byeResponse = receivedMessageBody.result as? com.telnyx.webrtc.sdk.verto.receive.ByeResponse
                    byeResponse?.let {
                        val terminationMessage = "Remote party ended call (${it.callId}). " +
                                                 "Reason: ${it.cause ?: "N/A"}" +
                                                 (it.sipCode?.let { sc -> " (SIP: $sc ${it.sipReason ?: ""})" } ?: "")
                        Log.i("TelnyxSDK_Bye", terminationMessage)
                        // This provides info that remote ended the call.
                        // The specific Call object's callStateFlow will also transition to CallState.DONE with this reason.
                    }
                }
                // Handle other methods like INVITE, ANSWER, LOGIN, CLIENT_READY etc.
            }
        }
        // Handle other statuses: ESTABLISHED, LOADING, DISCONNECT
    }
})
```

### Observing `Call.callStateFlow` (for Per-Call State and Reasons)

For each individual `Call` object, observe its `callStateFlow` to get detailed state transitions and associated reasons.

```kotlin
// Assuming 'myCall' is an active Call object
myCall.callStateFlow.collect { state ->
    when (state) {
        is CallState.ACTIVE -> {
            Log.i("CallState", "Call ${myCall.callId} is ACTIVE")
            // Update UI for active call
        }
        is CallState.DONE -> {
            val reason = state.reason
            val message = "Call ${myCall.callId} ENDED. " +
                          (reason?.let {
                              "Cause: ${it.cause ?: "Unknown"} (${it.causeCode ?: "N/A"}), " +
                              "SIP: ${it.sipCode ?: "N/A"} ${it.sipReason ?: ""}"
                          } ?: "No specific reason provided.")
            Log.i("CallState_Done", message)
            // Display termination reason to user, clean up call UI
        }
        is CallState.DROPPED -> {
            Log.w("CallState", "Call ${myCall.callId} DROPPED. Reason: ${state.callNetworkChangeReason.description}")
            // Inform user, potentially offer retry or end call UI
        }
        is CallState.RECONNECTING -> {
            Log.i("CallState", "Call ${myCall.callId} RECONNECTING. Reason: ${state.callNetworkChangeReason.description}")
            // Show reconnecting indicator
        }
        is CallState.ERROR -> {
            Log.e("CallState", "Call ${myCall.callId} entered ERROR state.")
            // Display error to user, clean up call UI
        }
        // Handle other states: NEW, CONNECTING, RINGING, HELD
        else -> Log.d("CallState", "Call ${myCall.callId} is now ${state.javaClass.simpleName}")
    }
}
```

## Best Practices for Error and State Handling

1.  **Observe Both Channels**: Use `socketResponseLiveData` for global SDK status/errors and `call.callStateFlow` for individual call lifecycle management.
2.  **Log Extensively**: During development, log error messages, call states, and reasons to aid in debugging.
3.  **Provide Clear User Feedback**: Translate technical error codes and states into user-understandable messages.
    *   For `CallState.DONE` with a `reason`, use the `cause`, `sipCode`, and `sipReason` to provide specific feedback (e.g., "User Busy", "Invalid Number", "Call Rejected: Restricted Area"). Refer to the [Telnyx Troubleshooting Guide](https://support.telnyx.com/en/articles/5025298-troubleshooting-call-completion) for common interpretations.
    *   For `CallState.DROPPED` or `CallState.RECONNECTING`, inform the user about network issues.
4.  **Implement Recovery/Retry Logic**: For network-related drops or gateway issues, consider implementing reconnection attempts or prompting the user.
5.  **Graceful Degradation**: If critical errors occur (e.g., persistent gateway failure), ensure your app handles this gracefully, perhaps by disabling calling features and informing the user.

## Additional Resources

- [Telnyx WebRTC Android SDK GitHub Repository](https://github.com/team-telnyx/telnyx-webrtc-android)
- [API Documentation](https://developers.telnyx.com/docs/v2/webrtc)
- [Telnyx Troubleshooting Guide for Call Completion](https://support.telnyx.com/en/articles/5025298-troubleshooting-call-completion) (Essential for interpreting SIP codes and call failure reasons)