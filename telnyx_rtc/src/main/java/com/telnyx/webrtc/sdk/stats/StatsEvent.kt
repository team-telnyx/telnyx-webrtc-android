package com.telnyx.webrtc.sdk.stats

import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Class that represents the StatsEvent object.
 */
data class StatsEvent(
    val event: String,
    val tag: String,
    val connectionId:String,
    val data: JsonObject,
    val timestamp: String =  getIso8601Timestamp()
    ) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("timestamp", timestamp)
        json.addProperty("event", event)
        json.addProperty("connectionId", connectionId)
        json.addProperty("tag", tag)
        json.add("data", data)
        return json
    }
}

private fun getIso8601Timestamp(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.UK)
    dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    return dateFormat.format(Date())
}