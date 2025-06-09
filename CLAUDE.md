# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the Telnyx Android WebRTC SDK - a real-time communication library for Android that enables voice calling functionality through WebRTC technology. The project consists of:

- **telnyx_rtc**: Main SDK module containing WebRTC implementation
- **telnyx_common**: Common shared code and utilities that wraps the main SDK
- **samples/**: Sample applications demonstrating SDK usage:
  - `compose_app`: Jetpack Compose implementation
  - `xml_app`: Traditional XML views implementation
  - `connection_service_app`: Connection service integration example (Deprecated)

## Code style
- Please reference the [Kotlin Style Guide](https://kotlinlang.org/docs/coding-conventions.html) for general Kotlin coding conventions.
- Please make sure new code adheres to the rules mentioned at /telnyx_rtc/config/detekt.yml

## Workflow
- Don't change the verto message json structure without confirming first. 
- When making changes to the SDK, ensure that you update the sample applications (xml_app and compose_app) accordingly to demonstrate the new functionality.
- Use the `telnyx_common` module to share code between the SDK and sample applications, such as utility functions, models, and constants.
- Follow the established architecture patterns for WebRTC, including peer connection management, ICE candidate handling, and call state management.
- Ensure that all new features and bug fixes are accompanied by appropriate unit tests in the `telnyx_rtc` module.
- Use the provided build scripts (`build.gradle`) to manage dependencies, build configurations, and testing tasks.

## Build Commands

### Core Build Tasks
```bash
# Build the entire project
./gradlew build

# Assemble all variants without running tests
./gradlew assemble

# Clean build artifacts
./gradlew clean
```

### Unit Testing
```bash
# Run all unit tests
./gradlew test

# Run specific test variants
./gradlew testDebugUnitTest
./gradlew testReleaseUnitTest
```

### Code Quality
```bash
# Run Detekt static analysis
./gradlew detekt

# Run lint checks
./gradlew lint
```

### Documentation
```bash
# Generate Dokka documentation
./gradlew dokkaHtml
```

## Project Architecture

### Core SDK Components (telnyx_rtc module)

1. **TelnyxClient**: Primary SDK entry point for authentication, connection management, and call orchestration
2. **Call**: Individual call management with state handling, audio controls, and call operations
3. **TxSocket**: WebSocket connection handling using Ktor for real-time communication
4. **Peer**: WebRTC peer connection wrapper managing media streams and ICE candidates
5. **Verto Protocol**: JSON-RPC messaging layer for call signaling (send/receive packages)

### Key Architecture Patterns

- **Observer Pattern**: SocketObserver for WebSocket events, PeerConnectionObserver for WebRTC events
- **State Management**: Call states managed through sealed classes and StateFlow/LiveData
- **Coroutines**: Async operations using Kotlin coroutines throughout socket and media handling
- **Dependency Injection**: Hilt setup for sample apps

### Authentication Flow
1. TelnyxClient.connect() establishes socket connection
2. credentialLogin() or tokenLogin() authenticates with Telnyx platform
3. CLIENT_READY socket event indicates successful authentication
4. Calls can then be initiated or received

### Call Flow Architecture
1. **Outbound**: newInvite() → SDP offer → ICE candidates → ANSWER received
2. **Inbound**: INVITE received → acceptCall() → SDP answer → ICE exchange
3. **State Management**: CallState enum tracks call progression (NEW → CONNECTING → ACTIVE → DONE)

### Push Notification Integration
- Firebase Cloud Messaging integration for incoming call notifications
- Push metadata handling for call recovery from background state
- Foreground service management for active calls

## Configuration Requirements

### Local Development Setup
Sample apps require `local.properties` file with test credentials:
```
TEST_SIP_USERNAME=your_username
TEST_SIP_PASSWORD=your_password
TEST_SIP_CALLER_NAME=your_name
TEST_SIP_CALLER_NUMBER=your_number
TEST_SIP_DEST_NUMBER=destination_number
```

### Required Permissions
- INTERNET
- RECORD_AUDIO  
- ACCESS_NETWORK_STATE
- POST_NOTIFICATIONS (Android 14+)
- FOREGROUND_SERVICE and FOREGROUND_SERVICE_PHONE_CALL (for call notifications)

## Dependencies & Technology Stack

- **WebRTC**: Custom Telnyx WebRTC library (com.telnyx.webrtc.lib:library:1.0.1)
- **Networking**: Ktor for WebSocket, Retrofit for HTTP, OkHttp
- **Serialization**: Gson for JSON processing
- **Async**: Kotlin Coroutines
- **DI**: Hilt (in sample apps)
- **Logging**: Timber
- **Push**: Firebase Cloud Messaging
- **Testing**: JUnit 4/5, Kotest, MockK, Robolectric

## Important Notes

- Minimum SDK: 23, Target SDK: 34, Compile SDK: 35
- Java 11 compatibility required
- ProGuard rules must include: `-keep class com.telnyx.webrtc.** { *; }`
- Network state monitoring for connection recovery
- Audio device management (speaker, earpiece, Bluetooth) built-in
- Multi-call support with UUID-based call identification