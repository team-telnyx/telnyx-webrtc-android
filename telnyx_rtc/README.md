# Telnyx Android WebRTC SDK

The Telnyx WebRTC SDK for Android enables developers to add real-time voice and video
communications capabilities to their Android applications. Built on top of WebRTC technology,
this SDK provides a high-level interface for establishing and managing calls through the
Telnyx network.

## Key Features
- Audio calls
- Call management (answer, reject, end)
- Push notification support
- Audio device management
- Network resilience and automatic reconnection
- Custom SIP headers support

## Package Structure

### Core Package (`com.telnyx.webrtc.sdk`)
Main components for call handling and client management:
- `TelnyxClient`: Main entry point for the SDK functionality
- `Call`: Represents and manages individual calls
- `Config`: SDK configuration settings

### Models Package (`com.telnyx.webrtc.sdk.model`)
Data models and enums:
- Call States (NEW, CONNECTING, ACTIVE, etc.)
- Audio Devices (BLUETOOTH, PHONE_EARPIECE, LOUDSPEAKER)
- Error Types (TOKEN_ERROR, CREDENTIAL_ERROR)
- Gateway States and Socket Status
- Logging Levels

### Peer Package (`com.telnyx.webrtc.sdk.peer`)
WebRTC peer connection management:
- ICE candidate handling
- Media stream management
- Connection state monitoring
- Audio track control
- Data channel support

### Socket Package (`com.telnyx.webrtc.sdk.socket`)
WebSocket communication:
- Secure WebSocket connections
- Automatic reconnection
- Message handling
- Connection state management
- Event-based communication

### Utilities Package (`com.telnyx.webrtc.sdk.utilities`)
Helper functions and utilities:
- Network connectivity monitoring
- Base64 encoding/decoding
- JSON serialization
- Configurable logging
- String extensions

### Verto Package (`com.telnyx.webrtc.sdk.verto`)
Verto protocol implementation:

#### Send Package
- Message formatting for outgoing communications
- Dialog parameter management
- Request construction
- Custom header support

#### Receive Package
- Message parsing for incoming communications
- Response status handling
- Error processing
- Event observation

## Usage Examples

### Initialize Client
```kotlin
val client = TelnyxClient(context)
```

### Make a Call
```kotlin
val call = client.newInvite(
    callerName = "John Doe",
    callerNumber = "+1234567890",
    destinationNumber = "+1987654321",
    clientState = "my-state"
)
```

### Answer a Call
```kotlin
client.acceptCall(
    callId = uuid,
    destinationNumber = "destination"
)
```

### End a Call
```kotlin
client.endCall(callId)
```

### Handle Audio Device
```kotlin
// Switch to speaker
call.onLoudSpeakerPressed()

// Check speaker status
val isSpeakerOn = call.getLoudSpeakerStatus()
```

### Monitor Call State
```kotlin
call.getCallState().observe(lifecycleOwner) { state ->
    when (state) {
        CallState.ACTIVE -> // Handle active call
        CallState.HELD -> // Handle held call
        CallState.DONE -> // Handle ended call
    }
}
```

## Network Requirements
- WebSocket connectivity to Telnyx servers
- UDP ports open for WebRTC media
- Stable internet connection for optimal call quality

## Dependencies
- WebRTC library
- OkHttp for WebSocket communication
- Gson for JSON handling
- Timber for logging

## Note
This SDK is designed for Android applications and requires minimum API level as specified in the build configuration. Ensure all required permissions are properly set in your Android manifest.