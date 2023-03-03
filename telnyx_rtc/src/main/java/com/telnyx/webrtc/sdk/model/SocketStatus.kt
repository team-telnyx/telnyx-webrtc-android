/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to detail Socket Status messages
 *
 * @property ESTABLISHED a connection to the socket has been established
 * @property MESSAGERECEIVED the socket has received a message
 * @property ERROR the socket has encountered an error
 * @property LOADING the socket is loading a connection
 * @property DISCONNECT when the socket is disconnect
 */
enum class SocketStatus {
    ESTABLISHED,
    MESSAGERECEIVED,
    ERROR,
    LOADING,
    DISCONNECT
}
