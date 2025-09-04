/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Represents the different connection states of the Telnyx client
 */
enum class ConnectionStatus {
    /**
     * Client is disconnected from the server
     */
    DISCONNECTED,

    /**
     * Client is connected to the server but not registered
     */
    CONNECTED,

    /**
     * Client is attempting to reconnect to the server
     */
    RECONNECTING,

    /**
     * Client is connected and registered, ready to make calls
     */
    CLIENT_READY
}
