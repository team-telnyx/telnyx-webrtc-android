package com.telnyx.webrtc.common.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calls_history")
data class CallHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val userProfileName: String,
    val callType: String, // "inbound" or "outbound"
    val destinationNumber: String,
    val date: Long // timestamp
)
