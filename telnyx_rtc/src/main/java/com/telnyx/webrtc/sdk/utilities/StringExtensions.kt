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

/**
 * Encodes a string to Base64 format.
 * 
 * @return The Base64 encoded string using UTF-8 charset
 */
fun String.encodeBase64(): String {
    return String(
        android.util.Base64.encode(this.toByteArray(), android.util.Base64.DEFAULT),
        StandardCharsets.UTF_8
    )
}

/**
 * Converts any object to its JSON string representation.
 * 
 * @return A JSON string representation of the object, or an empty string if conversion fails
 */
fun Any.toJsonString(): String {
    return try {
        GsonBuilder().serializeSpecialFloatingPointValues().create().toJson(this)
    }catch (e: JsonIOException){
        Timber.e(e)
        ""
    }
}

/**
 * Parses a JSON string into an object of type T.
 * 
 * @param T The type to parse the JSON string into
 * @return The parsed object of type T, or null if parsing fails
 */
inline fun <reified T> String.parseObject(): T? {
    return try {
        Gson().fromJson(this, object : TypeToken<T>() {}.type)
    } catch (ex: JsonIOException) {
        Timber.e(ex)
        null
    }
}

/**
 * Decodes a Base64 encoded string back to its original form.
 * 
 * @return The decoded string using UTF-8 charset
 */
fun String.decodeBase64(): String {
    return String(
        android.util.Base64.decode(this, android.util.Base64.DEFAULT),
        StandardCharsets.UTF_8
    )
}
