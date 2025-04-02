# Push Notification Quickstart with telnyx_common

The `telnyx_common` module provides a comprehensive implementation for handling push notifications in your Android application. This guide will help you quickly integrate Telnyx's push notification system using the ready-made components available in the `telnyx_common` module.

## Overview

The `telnyx_common` module offers a complete solution for handling push notifications for incoming calls, including:

- Receiving and parsing Firebase Cloud Messaging (FCM) notifications
- Displaying appropriate notifications for incoming calls
- Handling user interactions with notifications (answer, reject, end call)
- Managing notification channels and importance levels
- Supporting both modern CallStyle notifications and legacy notifications for backward compatibility

Instead of implementing these features from scratch, you can leverage the notification components in the `telnyx_common` module as a drop-in solution for your application.

## Key Components

### MyFirebaseMessagingService

`MyFirebaseMessagingService` is responsible for receiving and processing incoming Firebase Cloud Messaging (FCM) push notifications for calls.

**Key Features:**
- Parses incoming push notifications and extracts call metadata
- Handles different types of notifications (incoming calls, missed calls)
- Routes notifications to the appropriate handlers
- Supports both modern CallStyle notifications and legacy notifications

**Implementation:**
1. Register the service in your AndroidManifest.xml:
```xml
<service
    android:name="com.telnyx.webrtc.common.notification.MyFirebaseMessagingService"
    android:priority="10000"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

### CallNotificationService

`CallNotificationService` handles the creation and management of call notifications using Android's modern CallStyle API.

**Key Features:**
- Creates and manages notification channels with appropriate importance levels
- Displays incoming call notifications with answer/reject actions
- Shows ongoing call notifications with end call action
- Supports full-screen incoming call UI
- Configures appropriate sounds, vibration patterns, and priority levels

**Implementation:**
The service is automatically used by `MyFirebaseMessagingService` when an incoming call is received. No additional setup is required if you're using the `telnyx_common` module.

### CallNotificationReceiver

`CallNotificationReceiver` is a BroadcastReceiver that handles user interactions with call notifications.

**Key Features:**
- Processes answer, reject, and end call actions from notifications
- Routes actions to the appropriate handlers in your application
- Cancels notifications when actions are taken

**Implementation:**
1. Register the receiver in your AndroidManifest.xml:
```xml
<receiver
    android:name="com.telnyx.webrtc.common.notification.CallNotificationReceiver"
    android:enabled="true"
    android:exported="false" />
```

### NotificationsService

`NotificationsService` is a legacy notification service maintained for backward compatibility.

**Key Features:**
- Provides fallback notification handling for devices that don't support CallStyle
- Creates and manages notification channels
- Displays incoming call notifications with answer/reject actions
- Handles ringtone playback for incoming calls

**Implementation:**
1. Register the service in your AndroidManifest.xml and specify your main activity:
```xml
<service
    android:name="com.telnyx.webrtc.common.notification.NotificationsService">
    <meta-data
        android:name="activity_class_name"
        android:value="your.package.MainActivity" />
</service>
```

## Integration Steps

To integrate push notifications using the `telnyx_common` module:

1. **Set up Firebase Cloud Messaging (FCM)** in your project:
   - Follow the [Firebase setup guide](https://firebase.google.com/docs/cloud-messaging/android/client)
   - Add the necessary dependencies to your app's build.gradle file
   - Configure your Firebase project and download the google-services.json file

2. **Register the notification components** in your AndroidManifest.xml:
```xml
<service
    android:name="com.telnyx.webrtc.common.notification.MyFirebaseMessagingService"
    android:priority="10000"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>

<service
    android:name="com.telnyx.webrtc.common.notification.NotificationsService">
    <meta-data
        android:name="activity_class_name"
        android:value="your.package.MainActivity" />
</service>

<receiver
    android:name="com.telnyx.webrtc.common.notification.CallNotificationReceiver"
    android:enabled="true"
    android:exported="false" />
```

3. **Handle push notification actions** in your main activity:
```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // ...
    
    // Check if the activity was launched from a push notification
    intent?.let { handlePushNotificationIntent(it) }
}

override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    // Handle push notification intents when the activity is already running
    intent?.let { handlePushNotificationIntent(it) }
}

private fun handlePushNotificationIntent(intent: Intent) {
    val action = intent.getStringExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)
    val metadataJson = intent.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
    
    if (action != null && metadataJson != null) {
        val pushMetadata = Gson().fromJson(metadataJson, PushMetaData::class.java)
        
        when (action) {
            MyFirebaseMessagingService.ACT_ANSWER_CALL -> {
                // Answer the call using TelnyxViewModel
                viewModel.answerIncomingPushCall(this, pushMetadata)
            }
            MyFirebaseMessagingService.ACT_REJECT_CALL -> {
                // Reject the call using TelnyxViewModel
                viewModel.rejectIncomingPushCall(this, pushMetadata)
            }
            MyFirebaseMessagingService.ACT_OPEN_TO_REPLY -> {
                // Show UI for incoming call
                // This is called when the user taps the notification itself
            }
        }
    }
}
```

4. **Configure the CallForegroundService** for ongoing calls:
```xml
<service 
    android:name="com.telnyx.webrtc.common.service.CallForegroundService"
    android:enabled="true"
    android:exported="true"
    android:foregroundServiceType="phoneCall|microphone"
    android:permission="android.permission.FOREGROUND_SERVICE_PHONE_CALL"
    android:process=":call_service" />
```

## Sample App References

You can refer to our sample apps for complete implementations:

### Compose Sample App

The [Compose sample app](https://github.com/team-telnyx/telnyx-webrtc-android/tree/main/samples/compose_app) demonstrates how to integrate push notifications in a Jetpack Compose application:

- [AndroidManifest.xml](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/samples/compose_app/src/main/AndroidManifest.xml) - Shows how to register the notification components
- [MainActivity.kt](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/samples/compose_app/src/main/java/org/telnyx/webrtc/compose_app/MainActivity.kt) - Demonstrates handling push notification intents

### XML Sample App

The [XML sample app](https://github.com/team-telnyx/telnyx-webrtc-android/tree/main/samples/xml_app) shows the integration in a traditional XML-based Android application:

- [AndroidManifest.xml](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/samples/xml_app/src/main/AndroidManifest.xml) - Shows how to register the notification components
- [MainActivity.kt](https://github.com/team-telnyx/telnyx-webrtc-android/blob/main/samples/xml_app/src/main/java/org/telnyx/webrtc/xml_app/MainActivity.kt) - Demonstrates handling push notification intents

## Best Practices

1. **Permissions**: Ensure your app has the necessary permissions for notifications and foreground services:
   ```xml
   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_PHONE_CALL" />
   ```

2. **Notification Channels**: The `telnyx_common` module automatically creates appropriate notification channels, but you may want to customize them for your app's branding.

3. **Testing**: Test push notifications on different Android versions to ensure compatibility with both modern CallStyle notifications and legacy notifications.

4. **Background Processing**: Use the `CallForegroundService` to maintain calls when your app is in the background.

5. **Error Handling**: Implement proper error handling for push notification processing to ensure a smooth user experience.

## Troubleshooting

- If notifications are not appearing, check that notification permissions are granted and channels are properly configured.
- For issues with Firebase Cloud Messaging, verify that your FCM setup is correct and that you have the latest version of the Firebase SDK.
- If call notifications are not working correctly, ensure that the `activity_class_name` metadata in your AndroidManifest.xml points to the correct activity.
- For notification sound issues, check the audio settings on the device and verify that the notification channel is configured with the correct sound settings.
- Double-check the Telnyx portal to make sure push credentials are assigned properly.