/**
 * # Verto Send Package
 *
 * Contains classes for creating and sending Verto protocol messages.
 *
 * ## Key Components
 *
 * ### Message Creation
 * - [SendingMessageBody]: Base class for outgoing Verto messages
 * - [DialogParams]: Parameters for Verto dialog operations
 *
 * ### Request Handling
 * - [ParamRequest]: Formats parameters for Verto requests
 *
 * ## Features
 * - Message formatting
 * - Dialog parameter management
 * - Request construction
 * - Custom header support
 *
 * ## Usage Example
 * ```kotlin
 * val params = DialogParams(callId = uuid, destinationNumber = number)
 * val message = SendingMessageBody(id = uuid, method = "invite", params = params)
 * ```
 *
 * @see com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
 * @see com.telnyx.webrtc.sdk.verto.send.DialogParams
 */
package com.telnyx.webrtc.sdk.verto.send