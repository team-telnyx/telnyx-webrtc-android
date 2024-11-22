/**
 * # Socket Package
 *
 * Contains components for managing WebSocket connections to Telnyx servers.
 *
 * ## Key Components
 *
 * ### Socket Connection
 * - [TxSocket]: Manages WebSocket connections and message handling with Telnyx servers
 *
 * ### Event Handling
 * - [TxSocketListener]: Interface for handling socket events like connection state changes and message reception
 *
 * ## Features
 * - Secure WebSocket connections
 * - Automatic reconnection
 * - Message serialization/deserialization
 * - Connection state management
 * - Event-based communication
 *
 * ## Usage Example
 * ```kotlin
 * val socket = TxSocket(hostAddress, port)
 * socket.connect(listener)
 * socket.send(messageObject)
 * ```
 *
 * @see com.telnyx.webrtc.sdk.socket.TxSocket
 * @see com.telnyx.webrtc.sdk.socket.TxSocketListener
 */
package com.telnyx.webrtc.sdk.socket