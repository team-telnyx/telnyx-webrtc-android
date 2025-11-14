/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.Date

/**
 * Represents a single item in a conversation transcript with the AI assistant or user.
 *
 * @param id unique identifier for the transcript item
 * @param role role of the speaker - 'user' for user speech, 'assistant' for AI response
 * @param content the text content of the transcript item
 * @param timestamp timestamp when the transcript item was created
 * @param images optional list of image URLs associated with the transcript item
 * @param isPartial optional flag indicating if the item is a partial response
 */
data class TranscriptItem(
    val id: String,
    val role: String,
    val content: String,
    val timestamp: Date,
    val isPartial: Boolean = false,
    val images: List<String>? = null
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
