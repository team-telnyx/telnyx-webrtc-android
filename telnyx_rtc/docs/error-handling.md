# Error Handling Migration Guide

## Overview

The Android SDK now provides structured error and warning events via `TelnyxClient.errorFlow` and `TelnyxClient.warningFlow`. This brings parity with the JS SDK and Flutter SDK.

## What's New

### Error Flow

```kotlin
telnyxClient.errorFlow.collect { error ->
    // error.code — numeric code (e.g. 46001)
    // error.name — machine name (e.g. "LOGIN_FAILED")
    // error.message — short message (e.g. "Login failed")
    // error.description — full explanation
    // error.causes — list of possible causes
    // error.solutions — list of suggested fixes
    // error.fatal — true if terminal, false if SDK handles recovery
    // error.callId — call UUID if associated with a call
    // error.sessionId — session identifier
}
```

### Warning Flow

```kotlin
telnyxClient.warningFlow.collect { warning ->
    // warning.code — numeric code (e.g. 31001)
    // warning.name — machine name (e.g. "HIGH_RTT")
    // warning.message — short message
    // warning.description — full explanation
    // warning.causes — list of possible causes
    // warning.solutions — list of suggested fixes
    // warning.callId — call UUID if associated with a call
    // warning.sessionId — session identifier
}
```

## Error Codes

| Range | Category | Examples |
|---|---|---|
| 400xx | SDP negotiation | `SDP_CREATE_OFFER_FAILED` (40001) |
| 420xx | Media / device | `MEDIA_MICROPHONE_PERMISSION_DENIED` (42001) |
| 440xx | Call control | `HOLD_FAILED` (44001) |
| 450xx | WebSocket / transport | `WEBSOCKET_CONNECTION_FAILED` (45001) |
| 460xx | Authentication | `INVALID_CREDENTIALS` (46002) |
| 470xx | ICE restart | `ICE_RESTART_FAILED` (47001) |
| 480xx | Network | `NETWORK_OFFLINE` (48001) |
| 485xx | Session | `SESSION_NOT_REATTACHED` (48501) |
| 490xx | General | `UNEXPECTED_ERROR` (49001) |

## Warning Codes

| Range | Category | Examples |
|---|---|---|
| 310xx | Network quality | `HIGH_RTT` (31001) |
| 320xx | Connection / data-flow | `LOW_BYTES_RECEIVED` (32001) |
| 330xx | Call connection | `ICE_CONNECTIVITY_LOST` (33001) |
| 340xx | Authentication | `TOKEN_EXPIRING_SOON` (34001) |
| 350xx | Session / reconnection | `UNKNOWN_REATTACHED_SESSION` (35002) |
| 360xx | Signaling health | `SIGNALING_RECOVERY_REQUIRED` (36003) |

## Migration from Legacy API

### Before (deprecated)

```kotlin
telnyxClient.socketResponseFlow.collect { response ->
    if (response.status == SocketStatus.ERROR) {
        val message = response.errorMessage
        val code = response.errorCode
        // No description, causes, or solutions
    }
}
```

### After (recommended)

```kotlin
// Structured errors
telnyxClient.errorFlow.collect { error ->
    if (error.fatal) {
        // Show error UI, end call, or return to login
    } else {
        // SDK is handling recovery — show informational toast
    }
}

// Structured warnings
telnyxClient.warningFlow.collect { warning ->
    // Show degradation indicator or log for diagnostics
}
```

### Using Error Codes

```kotlin
import com.telnyx.webrtc.sdk.model.TelnyxErrorCodes

telnyxClient.errorFlow.collect { error ->
    when (error.code) {
        TelnyxErrorCodes.INVALID_CREDENTIALS -> {
            // Show login screen
        }
        TelnyxErrorCodes.NETWORK_OFFLINE -> {
            // Show network warning
        }
        TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED -> {
            // Show call failed dialog
        }
        else -> {
            // Generic error handling
        }
    }
}
```

## Backward Compatibility

- `SocketError` enum is deprecated but still functional
- `SocketResponse.error(msg, errorCode)` is deprecated but still works
- `socketResponseFlow` continues to emit all events including errors
- Both old and new APIs can be used simultaneously during migration

## Deprecated APIs

| API | Replacement |
|---|---|
| `SocketError` enum | `TelnyxErrorCodes` constants |
| `SocketResponse.error(msg, code)` | `TelnyxClient.errorFlow` |
| `response.errorCode` | `error.code` + `error.name` |
| `response.errorMessage` | `error.message` + `error.description` |
