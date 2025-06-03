### Telnyx Call

Class that represents a Call and handles all call related actions, including answering and ending a call.

### Creating a call invitation
In order to make a call invitation, you need to provide your callerName, callerNumber, the destinationNumber (or SIP credential), and your clientState (any String value).

```kotlin
   telnyxClient.call.newInvite(callerName, callerNumber, destinationNumber, clientState)
```

### Accepting a call
In order to be able to accept a call, we first need to listen for invitations. We do this by getting the Telnyx Socket Response as LiveData:

```kotlin
  fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? =
        telnyxClient.getSocketResponse()
```

We can then use this method to create a listener that listens for an invitation - in this example we assume getSocketResponse is a method within a ViewModel.

```kotlin
 mainViewModel.getSocketResponse()
            ?.observe(this, object : SocketObserver<ReceivedMessageBody>() {
                override fun onConnectionEstablished() {
                    // Handle a succesfully established connection 
                }
                
                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    when (data?.method) {
                        SocketMethod.CLIENT_READY.methodName -> {
                            // Fires once client has correctly been setup and logged into, you can now make calls. 
                        }

                        SocketMethod.LOGIN.methodName -> {
                           // Handle a successful login - Update UI or Navigate to new screen, etc.
                        }

                        SocketMethod.INVITE.methodName -> {
                           // Handle an invitation Update UI or Navigate to new screen, etc. 
                           // Then, through an answer button of some kind we can accept the call with:
                            val inviteResponse = data.result as InviteResponse
                            mainViewModel.acceptCall(inviteResponse.callId,  inviteResponse.callerIdNumber)
                        }

                        SocketMethod.ANSWER.methodName -> {
                            //Handle a received call answer - Update UI or Navigate to new screen, etc.
                        }

                        SocketMethod.BYE.methodName -> {
                           // Handle a call rejection or ending - Update UI or Navigate to new screen, etc.
                        }
                        SocketMethod.RINGING.methodName -> {
                            // Client Can simulate ringing state
                        }

                        SocketMethod.RINGING.methodName -> {
                            // Ringback tone is streamed to the caller
                            // early Media -  Client Can simulate ringing state
                        }
                    }
                }
                
                override fun onLoading() {
                    // Show loading dialog
                }

                override fun onError(errorCode: Int?, message: String?) {
                   // Handle errors - Update UI or Navigate to new screen, etc.
                   // errorCode provides additional context about the error type
                }

                override fun onSocketDisconnect() {
                    // Handle disconnect - Update UI or Navigate to login screen, etc.
                }

            })
```

When we receive a call we will receive an InviteResponse data class that contains the details we need to accept the call. We can then call the acceptCall method in TelnyxClient from our ViewModel:

### Handling Multiple Calls
The Telnyx WebRTC SDK allows for multiple calls to be handled at once. You can use the callId to differentiate the calls..

```kotlin
import java.util.UUID
// Retrieve all calls from the TelnyxClient
val calls: Map<UUID,Call> = telnyxClient.calls 

// Retrieve a specific call by callId
val currentCall: Call? = calls[callId]

```

With the current call object, you can perform actions such as:

1. Hold/UnHold `currentCall.onHoldUnholdPressed(callId: UUID)`
2. Mute/UnMute `currentCall.onMuteUnmutePressed()`
3. AcceptCall `currentCall.acceptCall(...)`
4. EndCall `currentCall.endCall(callId: UUID)`

The `Call` class is a fundamental part of the Telnyx WebRTC SDK, representing an active or pending call session. It provides properties to observe the call's state and methods to control the call, such as ending it, muting/unmuting audio, and managing hold states.

## Key Properties

- **`callId: UUID`**: A unique identifier for the call.
- **`sessionId: String`**: The session ID associated with the Telnyx connection.
- **`callStateFlow: StateFlow<CallState>`**: A Kotlin Flow that emits updates to the call's current state. This is the primary way to observe real-time changes to the call. States include:
    - `CallState.NEW`: The call has been locally initiated but not yet sent.
    - `CallState.CONNECTING`: The call is in the process of connecting.
    - `CallState.RINGING`: The call invitation has been sent, and the remote party is being alerted.
    - `CallState.ACTIVE`: The call is established and active.
    - `CallState.HELD`: The call is on hold.
    - `CallState.DONE(reason: CallTerminationReason?)`: The call has ended. The optional `reason` parameter provides details about why the call terminated (e.g., normal hangup, call rejected, busy, SIP error). `CallTerminationReason` contains `cause`, `causeCode`, `sipCode`, and `sipReason`.
    - `CallState.ERROR`: An error occurred related to this call.
    - `CallState.DROPPED(reason: CallNetworkChangeReason)`: The call was dropped, typically due to network issues. The `reason` (`CallNetworkChangeReason.NETWORK_LOST` or `CallNetworkChangeReason.NETWORK_SWITCH`) provides context.
    - `CallState.RECONNECTING(reason: CallNetworkChangeReason)`: The SDK is attempting to reconnect the call after a network disruption. The `reason` provides context.
- **`onCallQualityChange: ((CallQualityMetrics) -> Unit)?`**: A callback for real-time call quality metrics.
- **`audioManager: AudioManager`**: Reference to the Android `AudioManager` for controlling audio settings.
- **`peerConnection: Peer?`**: Represents the underlying WebRTC peer connection.

## Key Methods

- **`newInvite(...)`**: (Typically initiated via `TelnyxClient`) Initiates a new outgoing call.
- **`acceptCall(...)`**: (Typically initiated via `TelnyxClient`) Accepts an incoming call.
- **`endCall(callId: UUID)`**: Terminates the call. This is usually called on the `TelnyxClient` which then manages the specific `Call` object.
- **`onMuteUnmutePressed()`**: Toggles the microphone mute state.
- **`onLoudSpeakerPressed()`**: Toggles the loudspeaker state.
- **`onHoldUnholdPressed(callId: UUID)`**: Toggles the hold state for the call.
- **`dtmf(callId: UUID, tone: String)`**: Sends DTMF tones.

## Observing Call State

Applications should observe the `callStateFlow` to react to changes in the call's status and update the UI accordingly. For example, displaying call duration when `ACTIVE`, showing a "reconnecting" indicator when `RECONNECTING`, or presenting termination reasons when `DONE`.

```kotlin
// Example: Observing call state in a ViewModel or Composable
viewModelScope.launch {
    myCall.callStateFlow.collect { state ->
        when (state) {
            is CallState.ACTIVE -> {
                // Update UI to show active call controls
            }
            is CallState.DONE -> {
                // Call has ended, update UI
                // Access state.reason for termination details
                val reasonDetails = state.reason?.let {
                    "Cause: ${it.cause}, SIP Code: ${it.sipCode}"
                } ?: "No specific reason provided."
                Log.d("Call Ended", "Reason: $reasonDetails")
            }
            is CallState.DROPPED -> {
                // Call dropped, possibly show a message with state.reason.description
                Log.d("Call Dropped", "Reason: ${state.callNetworkChangeReason.description}")
            }
            is CallState.RECONNECTING -> {
                // Call is reconnecting, update UI
                Log.d("Call Reconnecting", "Reason: ${state.callNetworkChangeReason.description}")
            }
            // Handle other states like NEW, CONNECTING, RINGING, HELD, ERROR
            else -> { /* ... */ }
        }
    }
}
```

For more details on specific parameters and advanced usage, refer to the SDK's source code and the main `TelnyxClient` documentation.