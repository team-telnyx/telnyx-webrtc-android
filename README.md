# Telnyx Android WebRTC SDK

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
            
 ## Usage
 
 ### Telnyx Socket Connection
Before creating and logging in to the TelnyxClient you will have to initialize a Socket Connection for the TelnyxClient to interact with. We do this by instantiating TxSocket with host and port parameters:

```kotlin
 socketConnection = TxSocket(
      host_address = "rtc.telnyx.com",
      port = 14938
 )
```

### Telnyx Client
To initialize the TelnyxClient you will have to provide the application context as well as the socket connection that you just instanstiated. Once an instance is created, you can call the .connect() method to connect to the socket. An error will appear as a socket response if there is no network available:

```kotlin
  telnyxClient = TelnyxClient(context, socketConnection)
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

data class CredentialConfig(
    val sipUser: String,
    val sipPassword: String, 
    val sipCallerIDName: String?, // Your caller ID Name
    val sipCallerIDNumber: String?, //Your caller ID Number
    val ringtone: Int?, // Desired ringtone int resource ID
    val ringBackTone: Int?, // Desired ringback tone int resource ID
    val logLevel: LogLevel = LogLevel.NONE // SDK log level
    ) : TelnyxConfig()

data class TokenConfig(
    val sipToken: String, // JWT login token
    val sipCallerIDName: String?, // Your caller ID Name
    val sipCallerIDNumber: String?, //Your caller ID Number
    val ringtone: Int?, // Desired ringtone int resource ID
    val ringBackTone: Int?, // Desired ringback tone int resource ID
    val logLevel: LogLevel = LogLevel.NONE // SDK log level
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

```kotlin
 telnyxClient.call.acceptCall(callId, destinationNumber)
```


-----


Questions? Comments? Building something rad? [Join our Slack channel](https://joinslack.telnyx.com/) and share.

## License

[`MIT Licence`](./LICENSE) © [Telnyx](https://github.com/team-telnyx)

