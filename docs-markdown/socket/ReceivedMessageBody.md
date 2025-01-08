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