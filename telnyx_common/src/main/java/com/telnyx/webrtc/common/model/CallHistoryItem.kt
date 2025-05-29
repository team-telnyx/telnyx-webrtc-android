package com.telnyx.webrtc.common.model

data class CallHistoryItem(
    val id: Long,
    val userProfileName: String,
    val callType: CallType,
    val destinationNumber: String,
    val date: Long
)

enum class CallType {
    INBOUND,
    OUTBOUND
}

fun CallHistoryItem.isInbound(): Boolean = callType == CallType.INBOUND
fun CallHistoryItem.isOutbound(): Boolean = callType == CallType.OUTBOUND
