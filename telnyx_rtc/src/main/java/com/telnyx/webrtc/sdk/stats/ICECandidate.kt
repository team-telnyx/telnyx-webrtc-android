package com.telnyx.webrtc.sdk.stats

import com.google.gson.Gson
import com.google.gson.JsonElement

data class ICECandidate(
    /**
     * The address of the ICE candidate.
     */
    val address: String? = null,
    /**
     * The type of the ICE candidate.
     */
    val candidateType: String? = null,
    /**
     * The unique identifier for the ICE candidate.
     */
    val id: String? = null,
    /**
     * The port number of the ICE candidate.
     */
    val port: Int? = null,
    /**
     * The priority of the ICE candidate.
     */
    val priority: Long? = null,
    /**
     * The protocol used by the ICE candidate.
     */
    val protocol: String? = null,
    /**
     * The timestamp when the ICE candidate was generated.
     */
    val timestamp: Long? = null,
    /**
     * The transport identifier for the ICE candidate.
     */
    val transportId: String? = null,
    /**
     * The type of the ICE candidate, either local or remote.
     */
    val type: String? = null,
    /**
     * The URL of the ICE candidate.
     */
    val url: String? = null
) {
    companion object {
        fun createFromMap(map: Map<String, Any>): ICECandidate {
            val gson = Gson()
            val jsonString = gson.toJson(map)
            return gson.fromJson(jsonString, ICECandidate::class.java)
        }

        fun createFromJsonElement(jsonElement: JsonElement): ICECandidate {
            val gson = Gson()
            return gson.fromJson(jsonElement, ICECandidate::class.java)
        }
    }
}
