/**
 * # Models Package
 *
 * Contains data models and enums used throughout the Telnyx WebRTC SDK.
 *
 * ## Key Components
 *
 * ### Call States
 * - [CallState]: Represents different states a call can be in (NEW, CONNECTING, ACTIVE, etc.)
 *
 * ### Audio Management
 * - [AudioDevice]: Defines available audio output devices (BLUETOOTH, PHONE_EARPIECE, LOUDSPEAKER)
 *
 * ### Error Handling
 * - [SocketError]: Defines possible socket connection errors
 * - [CauseCode]: Represents different call termination causes
 *
 * ### Connection States
 * - [GatewayState]: Represents different states of the gateway connection
 * - [SocketStatus]: Defines possible WebSocket connection states
 *
 * ### Communication
 * - [SocketMethod]: Defines available WebSocket communication methods
 * - [LogLevel]: Controls SDK logging verbosity
 *
 * @see com.telnyx.webrtc.sdk.model.CallState
 * @see com.telnyx.webrtc.sdk.model.AudioDevice
 * @see com.telnyx.webrtc.sdk.model.SocketError
 */
package com.telnyx.webrtc.sdk.model