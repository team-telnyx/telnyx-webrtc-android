# Telnyx Android WebRTC SDK
[![](https://jitpack.io/v/team-telnyx/telnyx-webrtc-android.svg)](https://jitpack.io/#team-telnyx/telnyx-webrtc-android)
[![Unit Tests](https://github.com/team-telnyx/telnyx-webrtc-android/actions/workflows/unit_test_on_push.yml/badge.svg)](https://github.com/team-telnyx/telnyx-webrtc-android/actions/workflows/unit_test_on_push.yml)

Enable Telnyx real-time communication services on Android :telephone_receiver: :fire:

## Project structure: 

- SDK project: sdk module, containing all Telnyx SDK components as well as tests.
- Demo application: app module, containing a sample demo application utilizing the sdk module. 
       <p align="center">
               <img align="center" src="https://user-images.githubusercontent.com/9112652/114007708-78bb8a80-9859-11eb-9667-dfc2ef464ac1.PNG">
         </p>

## Project Setup:

1. Clone the repository
2. Open the cloned repository in Android Studio and hit the build button to build both the sdk and sample app:
        <p align="center">
               <img align="center" src="https://user-images.githubusercontent.com/9112652/113999521-05fae100-9852-11eb-92fa-42ce3040e420.PNG">
            </p>

4. Connect a device or start an emulated device and hit the run button
     <p align="center">
               <img align="center" src="https://user-images.githubusercontent.com/9112652/114007294-1d899800-9859-11eb-85ee-8803c570d6b1.PNG">
            </p>

5. Enjoy 😎
 
 <p align="center">
               <img align="center" width="33%" height="33%" src="https://user-images.githubusercontent.com/9112652/114009688-4448ce00-985b-11eb-9e0e-226ba6fab481.gif">
            </p>
            

 ## Using Jetpack Compose?
 Have a look at our Jetpack Compose reference application [here](https://github.com/team-telnyx/Telnyx-Android-Jetpack-Compose-WebRTC-Sample)

## SIP Credentials
In order to start making and receiving calls using the TelnyxRTC SDK you will need to get SIP Credentials:

1. Access to https://portal.telnyx.com/
2. Sign up for a Telnyx Account.
3. Create a Credential Connection to configure how you connect your calls.
4. Create an Outbound Voice Profile to configure your outbound call settings and assign it to your Credential Connection.

For more information on how to generate SIP credentials check the [Telnyx WebRTC quickstart guide](https://developers.telnyx.com/docs/v2/webrtc/quickstart). 
            
 ## Usage

### Telnyx Client
NOTE:
       Remember to add and handle INTERNET, RECORD_AUDIO and ACCESS_NETWORK_STATE permissions
       
   <p align="center">
               <img align="center" src="https://user-images.githubusercontent.com/9112652/117322479-f4731c00-ae85-11eb-9259-6333fc20b629.png">
            </p>

To initialize the TelnyxClient you will have to provide the application context. Once an instance is created, you can call the .connect() method to connect to the socket. An error will appear as a socket response if there is no network available:

```kotlin
  telnyxClient = TelnyxClient(context)
  telnyxClient.connect()
```

### Logging into Telnyx Client
 To log into the Telnyx WebRTC client, you'll need to authenticate using a Telnyx SIP Connection. Follow our [quickstart guide](https://developers.telnyx.com/docs/v2/webrtc/quickstart) to create **JWTs** (JSON Web Tokens) to authenticate. To log in with a token we use the tokinLogin() method. You can also authenticate directly with the SIP Connection `username` and `password` with the credentialLogin() method:
 
 ```kotlin
  telnyxClient.tokenLogin(tokenConfig)
                 //OR
  telnyxClient.credentialLogin(credentialConfig)             
 ```

**Note:** **tokenConfig** and **credentialConfig** are data classes that represent login settings for the client to use. They look like this:

```kotlin
sealed class TelnyxConfig

/**
 * Represents a SIP user for login - Credential based
 *
 * @property sipUser The SIP username of the user logging in
 * @property sipPassword The SIP password of the user logging in
 * @property sipCallerIDName The user's chosen Caller ID Name
 * @property sipCallerIDNumber The user's Caller ID Number
 * @property fcmToken The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 * @property customLogger Optional custom logger implementation to handle SDK logs
 * @property autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with valid credentials
 * @property debug whether or not to send client debug reports
 * @property reconnectionTimeout how long the app should try to reconnect to the socket server before giving up
 * @property region the region to use for the connection
 * @property fallbackOnRegionFailure whether or not connect to default region if the select region is not reachable
 */
data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val customLogger: TxLogger? = null,
    val autoReconnect: Boolean = false,
    val debug: Boolean = false,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true
    ) : TelnyxConfig()

/**
 * Represents a SIP user for login - Token based
 *
 * @property sipToken The JWT token for the SIP user.
 * @property sipCallerIDName The user's chosen Caller ID Name
 * @property sipCallerIDNumber The user's Caller ID Number
 * @property fcmToken The user's Firebase Cloud Messaging device ID
 * @property ringtone The integer raw value or uri of the audio file to use as a ringtone. Supports only raw file or uri
 * @property ringBackTone The integer raw value of the audio file to use as a ringback tone
 * @property logLevel The log level that the SDK should use - default value is none.
 * @property customLogger Optional custom logger implementation to handle SDK logs
 * @property autoReconnect whether or not to reattempt (3 times) the login in the instance of a failure to connect and register to the gateway with a valid token
 * @property debug whether or not to send client debug reports
 * @property reconnectionTimeout how long the app should try to reconnect to the socket server before giving up
 * @property region the region to use for the connection
 * @property fallbackOnRegionFailure whether or not connect to default region if the select region is not reachable
 */
data class TokenConfig(
    val sipToken: String,
    val sipCallerIDName: String?,
    val sipCallerIDNumber: String?,
    val fcmToken: String?,
    val ringtone: Any?,
    val ringBackTone: Int?,
    val logLevel: LogLevel = LogLevel.NONE,
    val customLogger: TxLogger? = null,
    val autoReconnect: Boolean = true,
    val debug: Boolean = false,
    val reconnectionTimeout: Long = 60000,
    val region: Region = Region.AUTO,
    val fallbackOnRegionFailure: Boolean = true
    ) : TelnyxConfig()

```

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

 ## Adding push notifications
The Telnyx Android Client WebRTC SDK makes use of Firebase Cloud Messaging in order to deliver push notifications. 
If you want to receive notifications for incoming calls on your Android mobile device you have to enable Firebase Cloud Messaging within your application.

In order to do this you need to:

       1. Set up a Firebase console account
       2. Create a Firebase project
       3. Add Firebase to your Android Application
       4. Setup a Push Credential within the Telnyx Portal
       5. Generate a Firebase Cloud Messaging instance token
       6. Send the token with your login message

### Providing our SDK with the FCM Token to receive Push Notifications

You will need to provide the `connect(..)` method with a `CredentialConfig` or `TokenConfig` that contains an fcmToken value (received from FirebaseMessaging like in the above code snippet).
Once the fcmToken has been provided, we can provide push notifications to the application when a call is received but the device is not actively connected to the socket. (eg. Killed or Backgrounded states)

```kotlin
telnyxClient = TelnyxClient(context)

val credentialConfig = CredentialConfig(
    sipUser = username,
    sipPassword = password,
    fcmToken = fcmToken
)

telnyxClient.connect(
   txPushMetaData = txPushMetaData,
   credentialConfig = credentialConfig,
)
```

For a detailed tutorial, please visit our official [Push Notification Docs](https://developers.telnyx.com/docs/voice/webrtc/android-sdk/push-notification/portal-setup)

## Custom Logging

The Telnyx WebRTC SDK allows you to implement your own custom logging solution by providing a `TxLogger` implementation. This gives you full control over how logs are handled, allowing you to route them to your own logging frameworks or analytics services.

### Using Custom Logger

1. Create a class that implements the `TxLogger` interface:

```kotlin
class MyCustomLogger : TxLogger {
    override fun log(level: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        // Implement your custom logging logic here
        // Example: Send logs to your analytics service
        MyAnalyticsService.log(
            level = level.name,
            tag = tag ?: "Telnyx",
            message = message,
            throwable = throwable
        )
    }
}
```

2. Pass your custom logger when creating the configuration:

```kotlin
// For credential-based login
val credentialConfig = CredentialConfig(
    sipUser = "your_sip_username",
    sipPassword = "your_sip_password",
    sipCallerIDName = "Your Name",
    sipCallerIDNumber = "Your Number",
    fcmToken = fcmToken,
    ringtone = R.raw.ringtone,
    ringBackTone = R.raw.ringbacktone,
    logLevel = LogLevel.ALL,           // Set desired log level
    customLogger = MyCustomLogger()    // Pass your custom logger
)

// For token-based login
val tokenConfig = TokenConfig(
    sipToken = "your_jwt_token",
    sipCallerIDName = "Your Name",
    sipCallerIDNumber = "Your Number",
    fcmToken = fcmToken,
    ringtone = R.raw.ringtone,
    ringBackTone = R.raw.ringbacktone,
    logLevel = LogLevel.ALL,           // Set desired log level
    customLogger = MyCustomLogger()    // Pass your custom logger
)
```

### Default Behavior

If no custom logger is provided, the SDK will use its default logging implementation based on Android's Log class. The `logLevel` parameter still controls which logs are generated, regardless of whether you're using a custom logger or the default one.

## Best Practices

### Handling Push Notifications
In order to properly handle push notifications, we recommend using a call type (Foreground Service)[https://developer.android.com/develop/background-work/services/foreground-services] with a broadcast receiver to show push notifications. An answer or reject call intent with `telnyxPushMetaData` can then be passed to the MainActivity for processing.
- Play a ringtone when a call is received from push notification using the `RingtoneManager`
``` kotlin
val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
 RingtoneManager.getRingtone(applicationContext, notification).play()
```
- Make Sure to set these flags for your pendingIntents, so the values get updated anytime when the notification is clicked
``` kotlin
   PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
```

### Android 14 Requirements
In order to receive push notifications on Android 14, you will need to add  the following permissions to your AndroidManifest.xml file and request a few at runtime:
   ``` xml
       // Request this permission at runtime
       <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
   
       // If you need to use foreground services, you will need to add the following permissions
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
       <uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL"/>
   
       // Configure foregroundservice and set the foreground service type
       // Remember to stopForegroundService when the call is answered or rejected
       <service
           android:name=".ForegroundService"
           android:foregroundServiceType="phoneCall"
           android:exported="true" />
   ```
### Handling Missed Call Notifications
The backend sends a missed call notification when a call is ended while the socket is not yet connected. It comes with the `Missed call!` message. In order to handle missed call notifications, you can use the following code snippet in the FirebaseMessagingService class:
   ``` kotlin
        const val Missed_Call = "Missed call!"
        val params = remoteMessage.data
        val objects = JSONObject(params as Map<*, *>)
        val metadata = objects.getString("metadata")
        val isMissedCall: Boolean = objects.getString("message").equals(Missed_Call) // 

        if(isMissedCall){
            Timber.d("Missed Call")
            val serviceIntent = Intent(this, NotificationsService::class.java).apply {
                putExtra("action", NotificationsService.STOP_ACTION)
            }
            serviceIntent.setAction(NotificationsService.STOP_ACTION)
            startMessagingService(serviceIntent)
            return
        }
   ```

### Handling Multiple Calls 
The Telnyx WebRTC SDK allows for multiple calls to be handled at once. You can use the callId to differentiate the calls. 
```kotlin
    import java.util.UUID
    // Retrieve all calls from the TelnyxClient
    val calls: Map<UUID,Call> = telnyxClient.calls 

    // Retrieve a specific call by callId
    val currentCall: Call? = calls[callId]
```

 ## ProGuard changes
 NOTE:
       In the case that you need to modify your application's proguard settings in order to obfuscate your code, such as we have done below:
    
#### **`app/build.gradle`**
```gradle
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
please keep in mind that you will need to add the following rules to the proguard-rules.pro file in your app in order for the SDK to continue functioning

#### **`app/proguard-rules.pro`**
```gradle
-keep class com.telnyx.webrtc.** { *; }
-dontwarn kotlin.Experimental$Level
-dontwarn kotlin.Experimental
-dontwarn kotlinx.coroutines.scheduling.ExperimentalCoroutineDispatcher
```

-----


Questions? Comments? Building something rad? [Join our Slack channel](https://joinslack.telnyx.com/) and share.

## License

[`MIT Licence`](./LICENSE) © [Telnyx](https://github.com/team-telnyx)

