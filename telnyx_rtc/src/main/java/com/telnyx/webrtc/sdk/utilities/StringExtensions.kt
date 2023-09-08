/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

/**
 * String extensions functions used by the SDK
 */
import android.util.MalformedJsonException
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonIOException
import com.google.gson.reflect.TypeToken
import timber.log.Timber
import java.nio.charset.StandardCharsets

fun String.encodeBase64(): String {
    return String(
        android.util.Base64.encode(this.toByteArray(), android.util.Base64.DEFAULT),
        StandardCharsets.UTF_8
    )
}

fun Any.toJsonString(): String {
    return try {
        GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(this)
    }catch (e: JsonIOException){
        Timber.e(e)
        ""
    }
}

inline fun <reified T> String.parseObject(): T? {
    return try {
        Gson().fromJson(this, object : TypeToken<T>() {}.type)
    } catch (ex: JsonIOException) {
        Timber.e(ex)
        null
    }
}

fun String.decodeBase64(): String {
    return String(
        android.util.Base64.decode(this, android.util.Base64.DEFAULT),
        StandardCharsets.UTF_8
    )
}
