## ReceivedMessageBody

A data class the represents the structure of every message received via the socket connection

```kotlin
data class ReceivedMessageBody(val method: String, val result: ReceivedResult?)
```

Where the params are:
* method the Telnyx Message Method - ie. INVITE, BYE, MODIFY, etc. @see [SocketMethod]
* result the content of the actual message in the structure provided via `ReceivedResult`


### SocketMethod
Enum class to detail the Method property of the response from the Telnyx WEBRTC client with the given [methodName]

```kotlin
  enum class SocketMethod(var methodName: String) {
  ANSWER("telnyx_rtc.answer"),
  ATTACH("telnyx_rtc.attach"),
  INVITE("telnyx_rtc.invite"),
  BYE("telnyx_rtc.bye"),
  MODIFY("telnyx_rtc.modify"),
  MEDIA("telnyx_rtc.media"),
  INFO("telnyx_rtc.info"),
  RINGING("telnyx_rtc.ringing"),
  PINGPONG("telnyx_rtc.ping"),
  CLIENT_READY("telnyx_rtc.clientReady"),
  GATEWAY_STATE("telnyx_rtc.gatewayState"),
  DISABLE_PUSH("telnyx_rtc.disable_push_notification"),
  ATTACH_CALL("telnyx_rtc.attachCalls"),
  LOGIN("login")
  }
```

* methodName is the Telnyx representation of the method, eg. telnyx_rtc.answer -> ANSWER

Method names:
* ANSWER the call has been answered by the destination
* INVITE send/receive an invitation that can then be answered or rejected
* BYE a user has ended the call
* MODIFY a modifier that allows the user to hold the call, etc
* MEDIA received media from destination, such as a dialtone
* MEDIA send information to the destination such as DTMF
* LOGIN the response to a login request.

The `ReceivedMessageBody` data class is a crucial part of the Telnyx WebRTC SDK, serving as a standardized wrapper for all messages received over the WebSocket connection. It provides a consistent structure for handling different types of Verto protocol messages.

## Structure

```kotlin
data class ReceivedMessageBody(
    val method: String,      // The Telnyx Message Method (e.g., "telnyx_rtc.invite", "telnyx_rtc.bye")
    val result: ReceivedResult? // The content of the actual message
)
```

- **`method: String`**: This field indicates the type of message received. It corresponds to one of the `SocketMethod` enums (e.g., `SocketMethod.INVITE`, `SocketMethod.ANSWER`, `SocketMethod.BYE`). Your application will typically use this field in a `when` statement to determine how to process the `result`.

- **`result: ReceivedResult?`**: This field holds the actual payload of the message. `ReceivedResult` is a sealed class, and the concrete type of `result` will depend on the `method`. For example:
    - If `method` is `SocketMethod.LOGIN.methodName`, `result` will be a `LoginResponse`.
    - If `method` is `SocketMethod.INVITE.methodName`, `result` will be an `InviteResponse`.
    - If `method` is `SocketMethod.ANSWER.methodName`, `result` will be an `AnswerResponse`.
    - If `method` is `SocketMethod.BYE.methodName`, `result` will be a `com.telnyx.webrtc.sdk.verto.receive.ByeResponse`. Importantly, this `ByeResponse` now includes detailed termination information such as `cause`, `causeCode`, `sipCode`, and `sipReason`, in addition to the `callId`.
    - Other `ReceivedResult` subtypes include `RingingResponse`, `MediaResponse`, and `DisablePushResponse`.

## Usage

When you observe `TelnyxClient.socketResponseLiveData`, you receive a `SocketResponse<ReceivedMessageBody>`. If the status is `SocketStatus.MESSAGERECEIVED`, the `data` field of `SocketResponse` will contain the `ReceivedMessageBody`.

```kotlin
telnyxClient.socketResponseLiveData.observe(this, Observer { response ->
    if (response.status == SocketStatus.MESSAGERECEIVED) {
        response.data?.let { receivedMessageBody ->
            Log.d("SDK_APP", "Method: ${receivedMessageBody.method}")
            when (receivedMessageBody.method) {
                SocketMethod.LOGIN.methodName -> {
                    val loginResponse = receivedMessageBody.result as? LoginResponse
                    // Process login response
                }
                SocketMethod.INVITE.methodName -> {
                    val inviteResponse = receivedMessageBody.result as? InviteResponse
                    // Process incoming call invitation
                }
                SocketMethod.BYE.methodName -> {
                    val byeResponse = receivedMessageBody.result as? com.telnyx.webrtc.sdk.verto.receive.ByeResponse
                    byeResponse?.let {
                        // Process call termination, access it.cause, it.sipCode, etc.
                        Log.i("SDK_APP", "Call ${it.callId} ended. Reason: ${it.cause}, SIP Code: ${it.sipCode}")
                    }
                }
                // Handle other methods...
            }
        }
    }
})
```

By checking the `method` and casting the `result` to its expected type, your application can effectively handle the diverse messages sent by the Telnyx platform.