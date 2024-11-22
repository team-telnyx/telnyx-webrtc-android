/**
 * # Verto Receive Package
 *
 * Contains classes for handling incoming Verto protocol messages.
 *
 * ## Key Components
 *
 * ### Message Handling
 * - [ReceivedMessageBody]: Base class for incoming Verto messages
 * - [ReceivedResult]: Processes results from Verto responses
 *
 * ### Socket Communication
 * - [SocketResponse]: Wraps socket responses with status information
 * - [SocketObserver]: Handles socket response events
 *
 * ## Features
 * - Message parsing
 * - Response status handling
 * - Error processing
 * - Event observation
 *
 * @see com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
 * @see com.telnyx.webrtc.sdk.verto.receive.SocketResponse
 */
package com.telnyx.webrtc.sdk.verto.receive