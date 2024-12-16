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
    val peerId: String,
    val connectionId:String,
    val data: JsonObject? = null,
    val timestamp: String =  getIso8601Timestamp(),
    val dataString: String? = null,
    val statsData: JsonObject? = null
    ) {
    fun toJson(): JsonObject {
        val json = JsonObject()
        json.addProperty("event", event)
        json.addProperty("tag", tag)
        json.addProperty("peerId", peerId)
        json.addProperty("connectionId", connectionId)
        dataString?.let {
            json.addProperty("data", dataString)
        } ?: run {
            json.add("data", data)
        }
        statsData?.let {
            json.add("statsObject", it)
        }
        json.addProperty("timestamp", timestamp)
        return json
    }
}

private fun getIso8601Timestamp(): String {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.UK)
    dateFormat.timeZone = TimeZone.getTimeZone("UTC")
    return dateFormat.format(Date())
}