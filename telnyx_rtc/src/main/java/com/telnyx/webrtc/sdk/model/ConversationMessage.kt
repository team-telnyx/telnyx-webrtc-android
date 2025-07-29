/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import com.google.gson.annotations.SerializedName

/**
 * Data class representing AI conversation parameters received from the socket
 */
data class AiConversationParams(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("item")
    val item: ConversationItem? = null,
    @SerializedName("delta")
    val delta: String? = null,
    @SerializedName("item_id")
    val itemId: String? = null,
    @SerializedName("widget_settings")
    val widgetSettings: WidgetSettings? = null
)

/**
 * Data class representing a conversation item
 */
data class ConversationItem(
    @SerializedName("id")
    val id: String? = null,
    @SerializedName("role")
    val role: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("content")
    val content: List<ConversationContent>? = null
)

/**
 * Data class representing conversation content
 */
data class ConversationContent(
    @SerializedName("transcript")
    val transcript: String? = null
)

/**
 * Data class representing widget settings for AI conversations
 */
data class WidgetSettings(
    @SerializedName("agent_thinking_text")
    val agentThinkingText: String? = null,
    @SerializedName("audio_visualizer_config")
    val audioVisualizerConfig: AudioVisualizerConfig? = null,
    @SerializedName("default_state")
    val defaultState: String? = null,
    @SerializedName("give_feedback_url")
    val giveFeedbackUrl: String? = null,
    @SerializedName("logo_icon_url")
    val logoIconUrl: String? = null,
    @SerializedName("position")
    val position: String? = null,
    @SerializedName("report_issue_url")
    val reportIssueUrl: String? = null,
    @SerializedName("speak_to_interrupt_text")
    val speakToInterruptText: String? = null,
    @SerializedName("start_call_text")
    val startCallText: String? = null,
    @SerializedName("theme")
    val theme: String? = null,
    @SerializedName("view_history_url")
    val viewHistoryUrl: String? = null
)

/**
 * Data class representing audio visualizer configuration
 */
data class AudioVisualizerConfig(
    @SerializedName("enabled")
    val enabled: Boolean? = null,
    @SerializedName("type")
    val type: String? = null
)