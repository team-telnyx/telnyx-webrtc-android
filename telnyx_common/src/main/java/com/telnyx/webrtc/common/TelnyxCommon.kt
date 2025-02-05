package com.telnyx.webrtc.common

import android.content.Context
import android.content.SharedPreferences
import com.telnyx.webrtc.sdk.TelnyxClient
import java.lang.ref.WeakReference

internal object TelnyxCommon {
    @Volatile
    private var telnyxClientWeakRef: WeakReference<TelnyxClient>? = null

    private var sharedPreferences: SharedPreferences? = null
    private const val SHARED_PREFERENCES_KEY = "TelnyxCommonSharedPreferences"

    fun getTelnyxClient(context: Context): TelnyxClient {
        return telnyxClientWeakRef?.get() ?: synchronized(this) {
            telnyxClientWeakRef?.get() ?: TelnyxClient(context.applicationContext).also {
                telnyxClientWeakRef = WeakReference(it)
            }
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
