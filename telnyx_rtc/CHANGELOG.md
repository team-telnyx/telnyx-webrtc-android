## [1.4.2](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.4.2) (2023-11-18)

### Enhancement

- ICE candidates are no longer added to the peer connection after the establishment of the call to prevent use of ICE candidates that are not negotiated in the SDP.

## [1.4.2-beta](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.4.2-beta) (2023-10-31)

### Enhancement

- Implemented websocket and RTC peer reconnection logic in the event of a network disconnect or network switch.

## [1.4.1](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.4.1) (2023-10-31)

### Enhancement

- Implemented websocket and RTC peer reconnection logic in the event of a network disconnect or network switch.

## [1.4.0](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.4.0) (2023-09-18)

### Enhancement

- Updated WebRTC library and added reconnection logic for Socket and Voice-SDK-ID.

## [1.3.9](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.3.9) (2023-08-27)

### Bug Fixing

- Fixed SSL error on Android 8.1.

## [1.3.8](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.3.8) (2023-08-15)

### Enhancement

- Improved stability of WebRTC session reconnection logic.

## [1.3.7](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.3.7) (2023-08-01)

### Enhancement

- Added new ICE server configuration and enhanced logging for troubleshooting.

## [1.3.6](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.3.6) (2023-07-20)

### Bug Fixing

- Refactored audio handling logic for improved compatibility with low-end devices.

## [1.3.5](https://github.com/team-telnyx/telnyx-webrtc-android/releases/tag/v1.3.5) - 2023-07-10

### Bug Fixing

- Fix JVM issue with the androidxlifecycle. The androidxlifecycle observer  onChanged(..) method was updated to  fun onChanged(value: T) which was previously fun onChanged(value: T) the cause of the issue.
