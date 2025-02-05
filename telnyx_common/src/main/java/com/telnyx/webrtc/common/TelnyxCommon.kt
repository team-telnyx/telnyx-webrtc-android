package com.telnyx.webrtc.common

import android.content.Context
import android.content.SharedPreferences
import com.telnyx.webrtc.sdk.TelnyxClient

internal object TelnyxCommon {
    @Volatile
    private var telnyxClient: TelnyxClient? = null

    private var sharedPreferences: SharedPreferences? = null
    private const val SHARED_PREFERENCES_KEY = "TelnyxCommonSharedPreferences"

    fun getTelnyxClient(context: Context): TelnyxClient {
        return telnyxClient ?: synchronized(this) {
            telnyxClient ?: TelnyxClient(context.applicationContext).also { telnyxClient = it }
        }
    }

    internal fun getSharedPreferences(context: Context): SharedPreferences {
        return sharedPreferences ?: synchronized(this) {
            sharedPreferences ?: context.getSharedPreferences(
                SHARED_PREFERENCES_KEY,
                Context.MODE_PRIVATE
            ).also { sharedPreferences = it }
        }
    }
}
