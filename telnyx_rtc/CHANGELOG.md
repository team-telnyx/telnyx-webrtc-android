## [3.4.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/3.4.0) (2025-02-18)

### Enhancement
- ICE Trickle Support for faster call setup
- Port TURN/STUN server configuration changes with custom ICE servers support
- Add DebugDataCollector for call debug logging
- Call Connection Benchmarking support
- Add answered_device_token parameter for push notification call answering
- Add DEV_TURN and DEV_STUN constants for development environment
- Support for starting calls with muted microphone (Invite/Answer muted)

### Bug Fixing
- Fix incorrect client ready state on failed login
- Retry with exponential backoff when server closes during reconnection

## [3.3.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/3.3.0) (2025-11-18)

### Enhancement
- Support for base64 encoded images in AI assistant messages
- Added AudioConstraints data class on invite and answer to allow for echoCancellation, noiseSuppression and autoGainControl

### Bug Fixing
- When TxSocket is now closing by close() instead of cancel() to prevent onError callback

## [3.2.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/3.2.0) (2025-10-22)

### Enhancement
- Connection State Exposure with DISCONNECTED, CONNECTED, RECONNECTING, CLIENT_READY states
- Expose Socket Connection Quality
- ICE Candidates Renegotiation

### Bug Fixing
- Preferred Audio Codec Implementation

## [3.1.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/3.1.0) (2025-08-27)

### Enhancement
- Anonymous Login + AI Agent related features 
- Preferred Codec implementation

## [3.0.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/3.0.0) (2025-07-17)

### Enhancement
- Implemented forceRelayCandidate parameter in Android WebRTC SDK.
- Migration from LiveData to Flows. (LiveData still supported for backward compatibility however methods are marked as deprecated)

## [2.0.2](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/2.0.2) (2025-06-17)

### Enhancement
- Add Region parameter available to be set. This allows developers to restrict connection only to some regions.
- Add parameter for prefetching ice candidates during call initiation.

### Bug Fixing
- Fixed an issue where the Termination Cause was always 'USER_BUSY' regardless of current call state. Now, when terminating an active call, the state will be 'NORMAL_CLEARING' and when rejecting an invite, the Termination Cause will be 'USER_BUSY'.

## [2.0.1](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/2.0.1) (2025-06-08)

### Enhancement
- Further enhance error handling by modifying SocketObserver's onError method signature to include the Error Code as well as the Message.

## [2.0.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/2.0.0) (2025-05-22)

### Enhancement
- Expose Call Termination Reasons in SDK and Surface Error Messages and Codes outside of the SDK. This allows developers to handle call termination reasons and error messages more effectively in their applications.

## [1.7.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.7.0) (2025-05-08)

### Enhancement
- Add Call Quality metrics to the SDK to provide insights into call performance and quality. This is handled on a per call basis via a debug parameter and is different to the debug parameter passed at a config level.

### Bug Fixing
- Ice Candidate Collection is now handled in the call object rather than universally in TelnyxClient, allowing for concurrent outgoing calls to be made without interference. This change improves the handling of multiple calls and ensures that each call's ICE candidates are managed independently.
- Connection is now recovered after Network lost

## [1.6.4](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.6.3) (2025-04-23)

### Bug Fixing
- Natively use transceivers to handle audio and video tracks instead of using separate streams. This change improves the handling of audio and video tracks in WebRTC calls, ensuring better compatibility with various devices and networks.
- Further enhance Ice Candidate Collection when answering calls to ensure more suitable candidates are used in the SDP.
- SDP Munging is now handled natively in the WebRTC library, which improves the overall performance and reliability of the call setup process.

## [1.6.3](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.6.3) (2025-04-08)

### Bug Fixing
- Enhanced Ice Candidate Collection when answering calls to ensure more suitable candidates are used in the SDP.

## [1.6.2](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.6.2) (2025-03-28)

### Bug Fixing
- Provide proper feedback when codec error occurs through the `onError` callback.
- Tag logs for easier identification.


## [1.6.1](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.6.1) (2025-03-20)

### Bug Fixing
- Fixed an issue where we were including local candidates in the SDP offer, which was causing issues with some networks.
- Adjusted the Peer class to also use the provided Logger when logging messages so that these can be used by a custom logger provided by the user (or our default logger if none is provided).

## [1.6.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.6.0) (2025-03-07)

### Enhancement
- Added reconnect timeout functionality to handle call reconnection failures.

## [1.5.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.5.0) (2025-03-07)

### Enhancement

- Disable Push Notification call is now simplified, no longer requiring parameters to be passed in the SDK. We instead use the last logged in user to disable push notifications. This Aligns with our iOS and Flutter implementations
- Added the ability to pass your own custom logger when connecting to redirect logs to your own logging system or log using a different chosen library.
- Added new CallStates to represent DROPPED and RECONNECTING states in regards to network drops while on a call.

### Bug Fixing
- Fixed an issue where listeners declared before connecting to the SDK would not receive events.

## [1.4.4](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.4) (2025-02-04)

### Enhancement

- Add Debug Stats ability by passing bool to Connection allowing you to see stats of calls in portal
- Remove BugSnag from SDK to reduce size as it is no longer required

## [1.4.3](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.3) (2025-01-22)

### Bug Fixing

- Fix missing STUN or TURN Candidates in SDP

## [1.4.2](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.2) (2024-11-18)

### Enhancement

- ICE candidates are no longer added to the peer connection after the establishment of the call to prevent use of ICE candidates that are not negotiated in the SDP.

## [1.4.2-beta](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.2-beta) (2024-10-31)

### Enhancement

- Implemented websocket and RTC peer reconnection logic in the event of a network disconnect or network switch.

## [1.4.1](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.1) (2024-10-31)

### Enhancement

- Implemented websocket and RTC peer reconnection logic in the event of a network disconnect or network switch.

## [1.4.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.4.0) (2024-09-18)

### Enhancement

- Updated WebRTC library and added reconnection logic for Socket and Voice-SDK-ID.

## [1.3.9](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.3.9) (2024-08-27)

### Bug Fixing

- Fixed SSL error on Android 8.1.

## [1.3.8](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.3.8) (2024-08-15)

### Enhancement

- Improved stability of WebRTC session reconnection logic.

## [1.3.7](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.3.7) (2024-08-01)

### Enhancement

- Added new ICE server configuration and enhanced logging for troubleshooting.

## [1.3.6](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.3.6) (2024-07-20)

### Bug Fixing

- Refactored audio handling logic for improved compatibility with low-end devices.

## [1.3.5](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/1.3.5) - 2024-07-10

### Bug Fixing

- Fix JVM issue with the androidxlifecycle. The androidxlifecycle observer  onChanged(..) method was updated to  fun onChanged(value: T) which was previously fun onChanged(value: T) the cause of the issue.
