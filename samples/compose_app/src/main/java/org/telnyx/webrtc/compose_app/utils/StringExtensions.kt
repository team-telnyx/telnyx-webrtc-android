package org.telnyx.webrtc.compose_app.utils

fun String?.capitalizeFirstChar(): String? {
    return this?.lowercase()?.replaceFirstChar { it.titlecase() }
}