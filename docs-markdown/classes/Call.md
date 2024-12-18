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

                override fun onError(message: String?) {
                   // Handle errors - Update UI or Navigate to new screen, etc.
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