## Push Notification App Setup

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

> **Note:** After pasting the above content, Kindly check and remove any new line added

You will need to provide the `connect(..)` method with a `txPushMetaData` value retrieved from push notification.
The `txPushMetaData` is necessary for push notifications to work.

```kotlin
  telnyxClient = TelnyxClient(context)
  telnyxClient.connect(txPushMetaData)
```

The final step is to create a MessagingService for your application. The MessagingService is the class that handles FCM messages and creates notifications for the device from these messages. You can read about the firebase messaging service class here:
https://firebase.google.com/docs/reference/android/com/google/firebase/messaging/FirebaseMessagingService

We have a sample implementation for you to take a look at here:
https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/app/src/main/java/com/telnyx/webrtc/sdk/utility/MyFirebaseMessagingService.kt

Once this class is created, remember to update your manifest and specify the newly created service like so:

https://firebase.google.com/docs/cloud-messaging/android/client#manifest

You are now ready to receive push notifications via Firebase Messaging Service.

## Best Practices
1. Handling Push Notifications : In order to properly handle push notifications, we recommend using a call type (Foreground Service)[https://developer.android.com/develop/background-work/services/foreground-services]
    with broadcast receiver to show push notifications. An answer or reject call intent with `telnyxPushMetaData` can then be passed to the MainActivity for processing.
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
