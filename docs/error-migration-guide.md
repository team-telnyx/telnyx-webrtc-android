# Migration Guide: Structured Errors & Warnings

The Android SDK now provides structured error and warning streams via `errorFlow` and `warningFlow`. This guide helps you migrate from the legacy `socketResponseFlow` error handling.

## What changed

The SDK now emits structured `TelnyxError` and `TelnyxWarning` objects through dedicated Kotlin Flows, aligned with the JS SDK's error/warning system. Each error carries a code, name, message, description, possible causes, recommended solutions, and a fatal flag.

## Before (legacy)

```kotlin
client.socketResponseFlow.collect { response ->
    if (response.status == SocketStatus.ERROR) {
        val message = response.errorMessage  // raw string
        val code = response.errorCode         // Int? — often null
        // App must guess what happened
        showError(message)
    }
}
```

## After (new API)

```kotlin
// Structured errors with full context
client.errorFlow.collect { error ->
    // error.code = 46001
    // error.name = "LOGIN_FAILED"
    // error.message = "Authentication failed"
    // error.description = "The login request was rejected..."
    // error.causes = ["Invalid credentials", "Expired token"]
    // error.solutions = ["Verify credentials", "Generate new token"]
    // error.fatal = true

    showError(error.message, error.solutions)
}

// Non-fatal warnings (new — no prior equivalent)
client.warningFlow.collect { warning ->
    // warning.code = 33001
    // warning.name = "ICE_CONNECTIVITY_LOST"
    // warning.message = "ICE connectivity lost"
    // warning.causes = ["Network change", "NAT timeout"]
    showWarning(warning.message)
}
```

## Backward compatibility

The legacy `socketResponseFlow` **still works** — `emitError()` emits through both `errorFlow` (new) and `socketResponseFlow` (legacy). Existing consumers do not break.

However, `SocketResponse.error()` is now `@Deprecated`. Migrate to `errorFlow` to get structured error context.

## Error code reference

See `TelnyxErrorRegistry` for the full list of error codes (25 codes across 9 categories) and `TelnyxWarningRegistry` for warning codes (27 codes across 6 categories).

## Quick reference

| Error code | Name | Fatal |
|-----------|------|-------|
| 40001 | SDP_CREATE_OFFER_FAILED | ✓ |
| 40002 | SDP_CREATE_ANSWER_FAILED | ✓ |
| 40003 | SDP_SET_LOCAL_DESCRIPTION_FAILED | ✓ |
| 40004 | SDP_SET_REMOTE_DESCRIPTION_FAILED | ✓ |
| 45001 | WEBSOCKET_CONNECTION_FAILED | ✓ |
| 45002 | WEBSOCKET_ERROR | ✗ |
| 45003 | RECONNECTION_EXHAUSTED | ✓ |
| 45004 | GATEWAY_FAILED | ✗ |
| 46001 | LOGIN_FAILED | ✓ |
| 46002 | INVALID_CREDENTIALS | ✓ |
| 48001 | NETWORK_OFFLINE | ✗ |
| 48501 | SESSION_NOT_REATTACHED | ✓ |
| 49001 | UNEXPECTED_ERROR | |

| Warning code | Name |
|-------------|------|
| 33001 | ICE_CONNECTIVITY_LOST |
| 33002 | ICE_GATHERING_TIMEOUT |
| 33004 | PEER_CONNECTION_FAILED |
| 34001 | TOKEN_EXPIRING_SOON |
| 36003 | SIGNALING_RECOVERY_REQUIRED |
| 36004 | MEDIA_RECOVERY_REQUIRED |
