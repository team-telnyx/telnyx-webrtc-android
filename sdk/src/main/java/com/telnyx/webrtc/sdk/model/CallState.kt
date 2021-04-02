package com.telnyx.webrtc.sdk.model

enum class CallState {
    // Call is created
    NEW,
    // Call is been connected to the remote client.
    CONNECTING,
    // Call is pending to be answered.
    RINGING,
    // Call is active when two clients are fully connected.
    ACTIVE,
    // User has held the call
    HELD,
    // When the call has  ended
    DONE,
}