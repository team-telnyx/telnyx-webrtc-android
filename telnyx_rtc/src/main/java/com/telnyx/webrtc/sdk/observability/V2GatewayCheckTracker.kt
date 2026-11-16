package com.telnyx.webrtc.sdk.observability

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Correlates only requested gateway checks, never unsolicited gateway notifications. */
internal class V2GatewayCheckTracker(
    private val started: (String) -> Unit,
    private val result: (String, String) -> Unit,
    private val failed: (String, String, Boolean) -> Unit
) {
    private val pending = mutableSetOf<String>()

    @Synchronized
    @Suppress("ReturnCount") // Non-gateway, malformed and uncorrelatable frames are separate filters.
    fun beforeWrite(text: String): String? {
        val frame = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        if (frame.string("method") != "telnyx_rtc.gatewayState") return null
        val id = frame.string("id") ?: return null
        if (pending.size >= MAX_PENDING && !pending.contains(id)) {
            val oldest = pending.first()
            pending.remove(oldest)
            failed(oldest, "Gateway check correlation capacity exceeded", false)
        }
        if (pending.add(id)) started(id)
        return id
    }

    @Synchronized
    fun rejected(id: String?) {
        if (id != null && pending.remove(id)) failed(id, "Gateway check write rejected", false)
    }

    @Synchronized
    fun received(frame: JsonObject, willRetry: Boolean) {
        val id = frame.string("id") ?: return
        if (!pending.contains(id) || (!frame.has("result") && !frame.has("error"))) return
        pending.remove(id)
        if (frame.has("error")) {
            // Server error text can contain credentials or payloads; report a stable reason only.
            failed(id, "Gateway check rejected by server", willRetry)
        } else {
            val state = runCatching {
                frame.getAsJsonObject("result").getAsJsonObject("params").get("state").asString
            }.getOrNull()
            if (state != null) result(id, state)
            else failed(id, "Gateway check response missing state", false)
        }
    }

    @Synchronized
    fun socketLost(willRetry: Boolean) {
        val ids = pending.toList()
        pending.clear()
        ids.forEach { failed(it, "Gateway check interrupted by socket closure", willRetry) }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private companion object {
        const val MAX_PENDING = 64
    }
}
