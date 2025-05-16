package org.telnyx.webrtc.xml_app.utils

import android.content.Context

fun Float.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()

fun Int.dpToPx(context: Context): Int =
    (this * context.resources.displayMetrics.density).toInt()