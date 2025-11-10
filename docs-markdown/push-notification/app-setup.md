# Push Notification App Setup
### Retrieving a Firebase Cloud Messaging Token

If the portal setup is complete and when firebase is properly integrated into your application, you will be able to retrieve a token with a method such as this:

```
private fun getFCMToken() {
FirebaseApp.initializeApp(this)
   FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
       if (!task.isSuccessful) {
           Timber.d("Fetching FCM registration token failed")
       }
       else if (task.isSuccessful){
           // Get new FCM registration token
           try {
               token = task.result
           } catch (e: IOException) {
               Timber.d(e)
           }
           Timber.d("FCM token received: $token")
       }
   }
}
```

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

The final step is to create a MessagingService for your application. The MessagingService is the class that handles FCM messages and creates notifications for the device from these messages. You can read about the firebase messaging service class here:
https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService

We have a sample implementation for you to take a look at here:
https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/app/src/main/java/com/telnyx/webrtc/sdk/utility/MyFirebaseMessagingService.kt

Once this class is created, remember to update your manifest and specify the newly created service like so:
https://firebase.google.com/docs/cloud-messaging/android/client#manifest

You are now ready to receive push notifications via Firebase Messaging Service.

### Handling Push Notifications once received - TxPushMetaData
The Telnyx SDK provides a `TxPushMetaData` object that can be used to handle push notifications when a call is received. You can parse the `TxPushMetaData` object to get the call details that then need to be provided to the `connect` method when reconnecting to the socket as a result of reacting to a push notification.

```kotlin
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("Message Received From Firebase: ${remoteMessage.data}")
        Timber.d("Message Received From Firebase Priority: ${remoteMessage.priority}")
        Timber.d("Message Received From Firebase: ${remoteMessage.originalPriority}")

        val params = remoteMessage.data
        val objects = JSONObject(params as Map<*, *>)
        val metadata = objects.getString("metadata")
        val isMissedCall: Boolean = objects.getString("message").equals(Missed_Call)

        if(isMissedCall){
            Timber.d("Missed Call")
            val serviceIntent = Intent(this, NotificationsService::class.java).apply {
                putExtra("action", NotificationsService.STOP_ACTION)
            }
            serviceIntent.setAction(NotificationsService.STOP_ACTION)
            startMessagingService(serviceIntent)
            return
        }

        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            putExtra("metadata", metadata)
        }
        startMessagingService(serviceIntent)
    }
```

You can see that in this case the TxPushMetaData is received from the 'metadata' field. This is then passed to our notification service for handling. A basic implementation of the Notification Service could look like so:

```kotlin
class NotificationsService : Service() {

   companion object {
      private const val CHANNEL_ID = "PHONE_CALL_NOTIFICATION_CHANNEL"
      private const val NOTIFICATION_ID = 1
      const val STOP_ACTION = "STOP_ACTION"
   }

   override fun onCreate() {
      super.onCreate()
      createNotificationChannel()
   }
   private var ringtone:Ringtone? = null

   private fun playPushRingTone() {
      try {
         val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
         ringtone =  RingtoneManager.getRingtone(applicationContext, notification)
         ringtone?.play()
      } catch (e: NotFoundException) {
         Timber.e("playPushRingTone: $e")
      }
   }


   override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

      val stopAction = intent?.action
      if (stopAction != null && stopAction == STOP_ACTION) {
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            ringtone?.stop()
         } else {
            stopForeground(true)
         }
         return START_NOT_STICKY
      }

      val metadata = intent?.getStringExtra("metadata")
      val telnyxPushMetadata = Gson().fromJson(metadata, PushMetaData::class.java)
      telnyxPushMetadata?.let {
         showNotification(it)
         playPushRingTone()

      }
      return START_STICKY
   }

   override fun onBind(intent: Intent?): IBinder? {
      return null
   }

   private fun createNotificationChannel() {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
         val name = "Phone Call Notifications"
         val description = "Notifications for incoming phone calls"
         val importance = NotificationManager.IMPORTANCE_HIGH
         val channel = NotificationChannel(CHANNEL_ID, name, importance)
         channel.description = description

         val notificationManager = getSystemService(NotificationManager::class.java)
         channel.apply {
            lightColor = Color.RED
            enableLights(true)
            enableVibration(true)
            setSound(null, null)
         }
         notificationManager.createNotificationChannel(channel)
      }
   }

   private fun showNotification(txPushMetaData: PushMetaData) {
      val intent = Intent(this, MainActivity::class.java).apply {
         flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
      }
      val pendingIntent: PendingIntent =
         PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE)

      val customSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

      val rejectResultIntent = Intent(this, MainActivity::class.java)
      rejectResultIntent.action = Intent.ACTION_VIEW
      rejectResultIntent.putExtra(
         MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
         MyFirebaseMessagingService.ACT_REJECT_CALL
      )
      rejectResultIntent.putExtra(
         MyFirebaseMessagingService.TX_PUSH_METADATA,
         txPushMetaData.toJson()
      )
      val rejectPendingIntent = PendingIntent.getActivity(
         this,
         MyFirebaseMessagingService.REJECT_REQUEST_CODE,
         rejectResultIntent,
         PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )

      val answerResultIntent = Intent(this, MainActivity::class.java)
      answerResultIntent.setAction(Intent.ACTION_VIEW)

      answerResultIntent.putExtra(
         MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
         MyFirebaseMessagingService.ACT_ANSWER_CALL
      )

      answerResultIntent.putExtra(
         MyFirebaseMessagingService.TX_PUSH_METADATA,
         txPushMetaData.toJson()
      )

      val answerPendingIntent = PendingIntent.getActivity(
         this,
         MyFirebaseMessagingService.ANSWER_REQUEST_CODE,
         answerResultIntent,
         PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
      )
      Timber.d("showNotification: ${txPushMetaData.toJson()}")


      val builder = NotificationCompat.Builder(this, CHANNEL_ID)
         .setSmallIcon(R.drawable.ic_stat_contact_phone)
         .setContentTitle("Incoming Call : ${txPushMetaData.callerName}")
         .setContentText("Incoming call from: ${txPushMetaData.callerNumber} ")
         .setPriority(NotificationCompat.PRIORITY_MAX)
         .setContentIntent(pendingIntent)
         .setSound(customSoundUri)
         .addAction(
            R.drawable.ic_call_white,
            MyFirebaseMessagingService.ACT_ANSWER_CALL, answerPendingIntent
         )
         .addAction(
            R.drawable.ic_call_end_white,
            MyFirebaseMessagingService.ACT_REJECT_CALL, rejectPendingIntent
         )
         .setOngoing(true)
         .setAutoCancel(false)
         .setCategory(NotificationCompat.CATEGORY_CALL)
         .setFullScreenIntent(pendingIntent, true)

      startForeground(
         NOTIFICATION_ID,
         builder.build(),
         ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
      )
   }
}
```

Ultimately though reacting to the push notification should cause the application to connect again and pass the handled `TxPushMetaData` object to the `connect` method like so:

```kotlin
telnyxClient.connect(
   txPushMetaData = txPushMetaData,
   credentialConfig = credentialConfig,
)
```   

If this is done correctly and you reconnect to the socket, you should receive the invite for the call on the socket as soon as you are reconnected

## Declining Push Notifications

The SDK provides two approaches for declining incoming calls from push notifications: the legacy approach and a new simplified approach.

### Legacy Approach (Complex Flow)

In the traditional approach, declining a push notification requires the following steps:

1. User taps decline on notification
2. App launches via PendingIntent
3. App connects to socket using the standard `connect()` method
4. App waits for invite message to be received
5. App calls `endCall()` to decline the call
6. App handles cleanup and remains open

**Example implementation:**
```kotlin
// In your notification decline handler
val intent = Intent(context, MainActivity::class.java).apply {
    putExtra("action", "decline_call")
    putExtra("metadata", txPushMetadata.toJson())
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
}
context.startActivity(intent)

// In MainActivity
if (intent.getStringExtra("action") == "decline_call") {
    val metadata = intent.getStringExtra("metadata")
    val txPushMetadata = Gson().fromJson(metadata, PushMetaData::class.java)
    
    // Connect and wait for invite
    telnyxClient.connect(
        txPushMetaData = txPushMetadata,
        credentialConfig = credentialConfig
    )
    
    // Listen for invite and then call endCall()
    telnyxClient.socketResponseLiveData.observe(this) { response ->
        if (response is SocketResponse.invite) {
            telnyxClient.endCall(response.callId)
        }
    }
}
```

### New Simplified Approach (Recommended)

The SDK now provides a much simpler way to decline incoming calls using the `connectWithDeclinePush()` method. This approach eliminates the need to wait for invite messages and manually send bye messages.

**How the New Flow Works:**
1. User taps decline on notification
2. App or background service calls `connectWithDeclinePush()`
3. SDK connects to socket with `decline_push: true` parameter
4. SDK automatically handles the decline process
5. SDK disconnects automatically
6. No need to wait for invites or manually call `endCall()`

**Example implementation:**
```kotlin
// In your notification decline handler
val txPushMetadata = // ... get from notification
val credentialConfig = CredentialConfig(
    sipUser = username,
    sipPassword = password,
    fcmToken = fcmToken
)

// Use the new decline method
telnyxClient.connectWithDeclinePush(
    config = credentialConfig,
    txPushMetaData = txPushMetadata.toJson()
)

// The SDK handles everything automatically - no need to wait for invites
```

### Benefits of the New Approach

- **Simplified implementation**: No need to handle complex invite/bye message flows
- **Automatic handling**: SDK manages the entire decline process internally
- **Reduced complexity**: No need to listen for socket events or manually call `endCall()`
- **Consistent behavior**: Decline process is handled uniformly by the SDK

### Choosing the Right Approach

- **Use the new approach** (`connectWithDeclinePush()`) for new implementations or when updating existing code
- **Legacy approach** may still be needed if you have specific requirements for handling the decline flow manually
- The new approach is recommended for most use cases as it reduces implementation complexity and potential errors

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
