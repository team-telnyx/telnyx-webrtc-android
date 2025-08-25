package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.model.ConversationItem
import com.telnyx.webrtc.sdk.model.WidgetSettings

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
): ParamRequest()
