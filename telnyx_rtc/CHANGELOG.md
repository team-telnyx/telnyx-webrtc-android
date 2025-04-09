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
