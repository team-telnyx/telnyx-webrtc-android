# Telnyx Common Module Quickstart

The `telnyx_common` module provides a ready-to-use implementation of the Telnyx WebRTC SDK for Android applications. This module is designed to be a drop-in solution for developers who want to quickly integrate Telnyx's voice calling capabilities into their Android applications without having to implement the low-level SDK interactions themselves.

## Overview

The `telnyx_common` module serves as a bridge between your application and the `telnyx_rtc` module (the core SDK). It provides:

1. A complete implementation of common WebRTC calling features
2. User-friendly abstractions for authentication, call management, and notifications
3. Ready-to-use UI components and services for handling calls
4. Foreground service implementation for maintaining calls when the app is minimized

## Integration

To use the `telnyx_common` module in your application:

1. Add the module as a dependency in your app's `build.gradle` file:

```gradle
dependencies {
    implementation project(':telnyx_common')
}
```

2. Initialize the module in your application class:

```kotlin
// In your Application class
override fun onCreate() {
    super.onCreate()
    TelnyxCommon.initialize(this)
}
```

## Key Components

### TelnyxViewModel

The `TelnyxViewModel` is the primary interface for interacting with the Telnyx WebRTC SDK. It provides methods for authentication, call management, and handling incoming calls.

#### Authentication Methods

- `credentialLogin(viewContext, profile, txPushMetaData, autoLogin)`: Authenticates a user using SIP credentials (username/password)
- `tokenLogin(viewContext, profile, txPushMetaData, autoLogin)`: Authenticates a user using a SIP token
- `connectWithLastUsedConfig(viewContext, txPushMetaData)`: Reconnects using the last successful authentication method
- `disconnect(viewContext)`: Disconnects the current session

#### Call Management Methods

- `sendInvite(viewContext, destinationNumber)`: Initiates an outgoing call to the specified number
- `answerCall(viewContext, callId, callerIdNumber)`: Answers an incoming call
- `rejectCall(viewContext, callId)`: Rejects an incoming call
- `endCall(viewContext)`: Ends the current active call
- `holdUnholdCurrentCall(viewContext)`: Toggles hold state for the current call
- `dtmfPressed(key)`: Sends DTMF tones during an active call

#### Push Notification Methods

- `answerIncomingPushCall(viewContext, txPushMetaData)`: Answers a call received via push notification
- `rejectIncomingPushCall(viewContext, txPushMetaData)`: Rejects a call received via push notification
- `disablePushNotifications(context)`: Disables push notifications for the current device

#### Profile Management Methods

- `setupProfileList(context)`: Initializes the list of user profiles
- `addProfile(context, profile)`: Adds a new user profile
- `deleteProfile(context, profile)`: Deletes an existing user profile
- `setCurrentConfig(context, profile)`: Sets the active user profile

#### Server Configuration Methods

- `changeServerConfigEnvironment(isDev)`: Switches between development and production environments

### CallForegroundService

The `CallForegroundService` is a foreground service that keeps your call active when the app is minimized. This is essential for maintaining call audio and preventing the system from killing the call process.

Key features:
- Maintains a persistent notification during active calls
- Handles audio routing when the app is in the background
- Provides call control actions directly from the notification

Usage:
```kotlin
// Start the service when a call begins
CallForegroundService.startService(context, pushMetaData)

// Stop the service when a call ends
CallForegroundService.stopService(context)

// Check if the service is running
val isCallActive = CallForegroundService.isServiceRunning(context)
```

### Notification System

The notification system provides a complete implementation for handling call notifications, including:

#### CallNotificationService

Handles the creation and management of notifications for:
- Incoming calls
- Ongoing calls
- Missed calls

The service creates appropriate notification channels and configures them with the correct importance levels, sounds, and vibration patterns.

#### CallNotificationReceiver

A `BroadcastReceiver` that handles user interactions with call notifications:
- Answering calls from the notification
- Rejecting calls from the notification
- Ending ongoing calls from the notification

#### MyFirebaseMessagingService

Handles incoming Firebase Cloud Messaging (FCM) push notifications for calls:
- Parses incoming push notifications
- Displays appropriate UI for incoming calls
- Routes call information to the appropriate handlers

## Implementation Example

Here's a basic example of how to use the `telnyx_common` module in an activity:

```kotlin
class CallActivity : AppCompatActivity() {
    private lateinit var viewModel: TelnyxViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)
        
        // Initialize the ViewModel
        viewModel = ViewModelProvider(this)[TelnyxViewModel::class.java]
        
        // Set up observers for call events
        viewModel.uiState.observe(this) { state ->
            when (state) {
                is TelnyxSocketEvent.OnClientReady -> handleClientReady()
                is TelnyxSocketEvent.OnIncomingCall -> handleIncomingCall(state.message)
                is TelnyxSocketEvent.OnCallAnswered -> handleCallAnswered(state.callId)
                is TelnyxSocketEvent.OnCallEnded -> handleCallEnded(state.message)
                // Handle other states...
            }
        }
        
        // Set up UI buttons
        dialButton.setOnClickListener {
            viewModel.sendInvite(this, phoneNumberInput.text.toString())
        }
        
        endCallButton.setOnClickListener {
            viewModel.endCall(this)
        }
        
        // Login with credentials
        val profile = Profile(
            sipUsername = "your_username",
            sipPassword = "your_password",
            callerIdName = "Your Name",
            callerIdNumber = "your_number"
        )
        viewModel.credentialLogin(this, profile, null)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Clean up if needed
    }
}
```

## Best Practices

1. **Call Lifecycle Management**: Always ensure you properly handle the call lifecycle, especially ending calls when they're complete to free up resources.

2. **Background Processing**: Use the `CallForegroundService` to maintain calls when your app is in the background.

3. **Error Handling**: Observe the `uiState` flow to catch and handle any errors that might occur during calls.

4. **User Permissions**: Ensure your app has the necessary permissions (microphone, notifications) before initiating calls.

5. **Battery Optimization**: Inform users to disable battery optimization for your app to ensure reliable call handling.

## Troubleshooting

- If calls disconnect when the app is minimized, ensure the `CallForegroundService` is properly started.
- For notification issues, check that notification permissions are granted and channels are properly configured.
- If authentication fails, verify the credentials and ensure the device has internet connectivity.