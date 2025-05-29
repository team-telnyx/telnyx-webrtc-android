package com.telnyx.webrtc.common.model

import com.google.gson.JsonObject
import java.util.Date

/**
 * Data class representing a websocket message with timestamp.
 *
 * @param message The JSON message received from the websocket.
 * @param timestamp The timestamp when the message was received.
 */
data class WebsocketMessage(
    val message: JsonObject,
    val timestamp: Date = Date()
)
