# Telnyx Android WebRTC SDK
[![](https://jitpack.io/v/team-telnyx/telnyx-webrtc-android.svg)](https://jitpack.io/#team-telnyx/telnyx-webrtc-android)
[![Unit Tests](https://github.com/team-telnyx/telnyx-webrtc-android/actions/workflows/unit_test_on_push.yml/badge.svg)](https://github.com/team-telnyx/telnyx-webrtc-android/actions/workflows/unit_test_on_push.yml)

Enable Telnyx real-time communication services on Android :telephone_receiver: :fire:

# Android Client SDK

Enable Telnyx real-time communication services on Android.

## Project structure: 

- SDK project: sdk module, containing all Telnyx SDK components as well as tests.
- Demo application: app module, containing a sample demo application utilizing the sdk module. 

## Project Setup:

1. Clone the repository
2. Open the cloned repository in Android Studio and hit the build button to build both the sdk and sample app:
3. Connect a device or start an emulated device and hit the run button

## Usage

1. Add Jitpack.io as a repository within your root level build file:
```java
allprojects {
                repositories {
                        ...
                        maven { url 'https://jitpack.io' }
                }
        }
```

2. Add the dependency within the app level build file:

```java
dependencies {
        implementation 'com.github.team-telnyx:telnyx-webrtc-android:Tag'
}
```

Tag should be replaced with the release version. 

Then, import the WebRTC SDK into your application code at the top of the class:
```bash
import com.telnyx.webrtc.sdk.*
```

The ‘*’ symbol will import the whole SDK which will then be available for use within that class. 

NOTE: Remember to add and handle INTERNET, RECORD_AUDIO and ACCESS_NETWORK_STATE permissions in order to properly use the SDK

### Telnyx Client
To initialize the TelnyxClient you will have to provide the application context. Once an instance is created, you can call the .connect() method to connect to the socket. An error will appear as a socket response if there is no network available:

```kotlin
  telnyxClient = TelnyxClient(context)
  telnyxClient.connect()
```

### Logging into Telnyx Client
 To log into the Telnyx WebRTC client, you'll need to authenticate using a Telnyx SIP Connection. Follow [this guide](/docs/voice/webrtc/auth/jwt) to create **JWTs** (JSON Web Tokens) to authenticate. To log in with a token we use the tokinLogin() method. You can also authenticate directly with the SIP Connection `username` and `password` with the credentialLogin() method:
 
 ```java
  telnyxClient.tokenLogin(tokenConfig)
                 //OR
  telnyxClient.credentialLogin(credentialConfig)             
 ```

**Note:** **tokenConfig** and **credentialConfig** are data classes that represent login settings for the client to use. They look like this:

```java
sealed class TelnyxConfig

data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String, 
    val sipCallerIDName: String?, // Your caller ID Name
    val sipCallerIDNumber: String?, //Your caller ID Number
    val ringtone: Int?, // Desired ringtone int resource ID
    val ringBackTone: Int?, // Desired ringback tone int resource ID
    val logLevel: LogLevel = LogLevel.NONE, // SDK log level
    val autoReconnect: Boolean = false, // whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials
    val debug: Boolean = false // whether or not send client debug reports
    ) : TelnyxConfig()

data class TokenConfig(
    val sipToken: String, // JWT login token
    val sipCallerIDName: String?, // Your caller ID Name
    val sipCallerIDNumber: String?, //Your caller ID Number
    val ringtone: Int?, // Desired ringtone int resource ID
    val ringBackTone: Int?, // Desired ringback tone int resource ID
    val logLevel: LogLevel = LogLevel.NONE, // SDK log level
    val autoReconnect: Boolean = false, // whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials
    val debug: Boolean = false // whether or not send client debug reports
    ) : TelnyxConfig()

```

### Creating a call invitation
In order to make a call invitation, you need to provide your callerName, callerNumber, the destinationNumber (or SIP credential), and your clientState (any String value).

```java
   telnyxClient.call.newInvite(callerName, callerNumber, destinationNumber, clientState)
```

### Accepting a call
In order to be able to accept a call, we first need to listen for invitations. We do this by getting the Telnyx Socket Response as LiveData:

```java
  fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? =
        telnyxClient.getSocketResponse()
```

We can then use this method to create a listener that listens for an invitation - in this example we assume getSocketResponse is a method within a ViewModel.

```java
 mainViewModel.getSocketResponse()
            ?.observe(this, object : SocketObserver<ReceivedMessageBody>() {
                override fun onConnectionEstablished() {
                    // Handle a succesfully established connection 
                }
                
                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    when (data?.method) {
                        SocketMethod.LOGIN.methodName -> {
                           // Handle a successfull login - Update UI or Navigate to new screen, etc.
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
                    }
                }
```

When we receive a call we will receive an InviteResponse data class that contains the details we need to accept the call. We can then call the acceptCall method in TelnyxClient from our ViewModel:

```java
 telnyxClient.call.acceptCall(callId, destinationNumber)
```

-----

### Methods: 


**.newInvite(callerName, callerNumber, destinationNumber, clientState)** 

Initiates a new call invitation, sending out an invitation to the destinationNumber via the Telnyx Socket Connection



<table class="table">
<tbody>
    <tr>
        <td>Name</td>
        <td>Type</td>
        <td>Required</td>
        <td>Description</td>
    </tr>
    <tr>
        <td>callerName</td>
        <td>String</td>
        <td>required</td>
        <td>Your caller name</td>
    </tr>
    <tr>
        <td>callerNumber</td>
        <td>String</td>
        <td>required</td>
        <td>Your caller number</td>
    </tr>
    <tr>
        <td>destinationNumber</td>
        <td>String</td>
        <td>required</td>
        <td>The person you are calling. Either their number or SIP username</td>
    </tr>
    <tr>
        <td>clientState</td>
        <td>String</td>
        <td>required</td>
        <td>A state you would like to convey to the person you are calling.</td>
    </tr>
</tbody>
</table>



```java
telnyxClient.call?.newInvite(callerName, callerNumber, destinationNumber, clientState)
```


**.acceptCall(callId, destinationNumber)** 
Accepts an incoming call. Local user responds with both local and remote SDPs



<table class="table">
<tbody>
    <tr>
        <td>Name</td>
        <td>Type</td>
        <td>Required</td>
        <td>Description</td>
    </tr>
    <tr>
        <td>callId</td>
        <td>UUID</td>
        <td>required</td>
        <td>ID of Call to respond to, this will be in a JSON</td>
    </tr>
    <tr>
        <td>destinationNumber</td>
        <td>String</td>
        <td>required</td>
        <td>Number or SIP username of the person calling,  this will be in a JSON Object returned by the socket as an invitation</td>
    </tr>
</tbody>
</table>



```java
 telnyxClient.call.acceptCall(callId, destinationNumber)
```


**.endCall(callID)** 
Ends an ongoing call with a provided callID, the unique UUID belonging to each call



<table class="table">
<tbody>
    <tr>
        <td>Name</td>
        <td>Type</td>
        <td>Required</td>
        <td>Description</td>
    </tr>
    <tr>
        <td>callId</td>
        <td>UUID</td>
        <td>required</td>
        <td>ID of Call to end. Each instance of a call has a callId parameter.</td>
    </tr>
</tbody>
</table>



```java
telnyxClient.call.endCall(callId)
```



**.getCallState()** 
Returns call state live data. This can be used to update UI. CallStates can be as follows: `NEW`, `CONNECTING`, `RINGING`, `ACTIVE`, `HELD` or `DONE`. 



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]
var currentCallState = currentCall.getCallState()
```



**.onMuteUnmutePressed()** 
Either mutes or unmutes the AudioManager based on the current muteLiveData value



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]

currentCall.onMuteUnmutePressed()
```



**.getIsMuteStatus()** 
Returns mute state live data. This can either be true or false.



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]

var isMute = currentCall.getIsMuteStatus()
```



**.onHoldUnholdPressed(callID)** 
Either places a call on hold, or unholds a call based on the current holdLiveData value.



<table class="table">
<tbody>
    <tr>
        <td>Name</td>
        <td>Type</td>
        <td>Required</td>
        <td>Description</td>
    </tr>
    <tr>
        <td>callId</td>
        <td>UUID</td>
        <td>required</td>
        <td>ID of Call to hold or unhold.</td>
    </tr>
</tbody>
</table>



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]
 
currentCall.onMuteUnmutePressed(callID)
```



**.getIsOnHoldStatus()** 
Returns hold state live data. This can either be true or false.



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]
 
var isOnHold = currentCall.getIsOnHoldStatus()
```


**.onLoudSpeakerPressed()** 
Either enables or disables the AudioManager loudspeaker mode based on the current loudSpeakerLiveData value.



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]
 
currentCall.onLoudSpeakerPressed()
```



**.getIsOnLoudSpeakerStatus()** 
Returns loudspeaker state live data. This can either be true or false.



```java
var calls = telnyxClient.getActiveCalls()
currentCall = calls[callID]
 
var isLoudSpeaker = currentCall.getIsOnLoudSpeakerStatus()
```



 ## ProGuard changes
 NOTE:
       In the case that you need to modify your application's proguard settings in order to obfuscate your code, such as we have done below:
    
#### **`app/build.gradle`**

```java
buildTypes {
        release {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            jniDebuggable true
        }
        debug {
            minifyEnabled true
            shrinkResources true
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
            debuggable true
            jniDebuggable true
        }
    }
```

Please keep in mind that you will need to add the following rules to the proguard-rules.pro file in your app in order for the SDK to continue functioning

#### **`app/proguard-rules.pro`**

```java
-keep class org.webrtc.** { *; }
-keep class com.telnyx.webrtc.sdk.** { *; }
```

## Additional Resources

- [Android Precompiled WebRTC Library](/docs/voice/webrtc/android-sdk/precompiled-library) - For developers who need more control over WebRTC implementation
- [WebRTC Official Documentation](https://webrtc.org/getting-started/overview)
- [Official SDK Documentation](https://developers.telnyx.com/docs/voice/webrtc/android-sdk/quickstart)

Questions? Comments? Building something rad? <a href="https://joinslack.telnyx.com/">Join our Slack channel</a> and share.

## License

[`MIT Licence`](./LICENSE) © [Telnyx](https://github.com/team-telnyx)

