package org.telnyx.webrtc.compose_app.utils

import java.text.SimpleDateFormat
import java.util.*

object Utils {
    fun formatCallHistoryItemDate(date: Long): String {
        return SimpleDateFormat("YYYY-MM-dd HH:mm:ss", Locale.getDefault()).format(date)
    }
}