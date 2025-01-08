# Module telnyx_rtc

Telnyx WebRTC Android SDK module provides real-time voice communications capabilities for Android applications. It handles WebRTC connections, call management, and communication with Telnyx servers.

# Package com.telnyx.webrtc.sdk

The root package containing core components for managing WebRTC communications through Telnyx network.

# Package com.telnyx.webrtc.sdk.model

Contains data models and enums used throughout the SDK for representing call states, audio devices, error types, and other essential data structures.

# Package com.telnyx.webrtc.sdk.peer

Handles WebRTC peer connections, including ICE candidates management, media streams, and connection state monitoring.

# Package com.telnyx.webrtc.sdk.socket

Manages WebSocket connections to Telnyx servers, providing secure communication channels for signaling and call control.

# Package com.telnyx.webrtc.sdk.utilities

Contains utility functions for network monitoring, string operations, and logging functionality.

# Package com.telnyx.webrtc.sdk.verto

Implements the Verto protocol for WebRTC signaling.

# Package com.telnyx.webrtc.sdk.verto.receive

Handles incoming Verto protocol messages and responses.

# Package com.telnyx.webrtc.sdk.verto.send

Manages outgoing Verto protocol messages and requests.

The root package provides the main client interface and call management functionality.

## Features

The SDK provides the following key features:
* Audio calls with support for different audio devices (speaker, earpiece, bluetooth)
* Call management (make, answer, reject, end calls)
* Push notification support for incoming calls
* Network resilience with automatic reconnection
* Custom SIP headers support
* Configurable logging levels

## Quick Start

Here's a basic example of using the SDK:

```kotlin
// Initialize client
val client = TelnyxClient(context)

// Make a call
val call = client.newInvite(
    callerName = "John Doe",
    callerNumber = "+1234567890",
    destinationNumber = "+1987654321",
    clientState = "my-state"
)

// Answer an incoming call
client.acceptCall(
    callId = uuid,
    destinationNumber = "destination"
)

// End a call
client.endCall(callId)
```

## Architecture

The SDK is organized into several key packages:
* `model` - Data models and enums for call states, audio devices, etc.
* `peer` - WebRTC peer connection management
* `socket` - WebSocket communication with Telnyx servers
* `utilities` - Helper functions and network monitoring
* `verto` - Implementation of the Verto protocol for signaling