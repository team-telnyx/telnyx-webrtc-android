/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.google.gson.*
import com.google.gson.reflect.TypeToken
import com.telnyx.webrtc.sdk.model.ConversationContent
import java.lang.reflect.Type

/**
 * Custom Gson deserializer for conversation content that can handle both:
 * - Array format: [{"type": "input_text", "text": "hello"}]
 * - String format: "hello"
 */
class
ConversationContentDeserializer : JsonDeserializer<List<ConversationContent>?> {
    
    override fun deserialize(
        json: JsonElement?,
        typeOfT: Type?,
        context: JsonDeserializationContext?
    ): List<ConversationContent>? {
        if (json == null || json.isJsonNull) {
            return null
        }
        
        return try {
            when {
                // Handle array format: [{"type": "input_text", "text": "hello"}]
                json.isJsonArray -> {
                    val listType = object : TypeToken<List<ConversationContent>>() {}.type
                    context?.deserialize(json, listType)
                }
                
                // Handle string format: "hello"
                json.isJsonPrimitive && json.asJsonPrimitive.isString -> {
                    val textContent = json.asString
                    listOf(
                        ConversationContent(
                            type = "text",
                            text = textContent,
                            transcript = null
                        )
                    )
                }
                
                // Handle object format: {"type": "input_text", "text": "hello"}
                json.isJsonObject -> {
                    val content = context?.deserialize<ConversationContent>(json, ConversationContent::class.java)
                    content?.let { listOf(it) }
                }
                
                else -> null
            }
        } catch (e: Exception) {
            // Log the error and return null to prevent crashes
            Logger.e(
                message = "Error deserializing conversation content: ${e.message}, JSON: $json"
            )
            null
        }
    }
}
