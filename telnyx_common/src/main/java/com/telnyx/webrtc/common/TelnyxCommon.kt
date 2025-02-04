package com.telnyx.webrtc.common

import android.content.Context
import com.telnyx.webrtc.sdk.TelnyxClient
import java.lang.ref.WeakReference

internal object TelnyxCommon {
    @Volatile
    private var telnyxClientWeakRef: WeakReference<TelnyxClient>? = null

    fun getTelnyxClient(context: Context): TelnyxClient {
        return telnyxClientWeakRef?.get() ?: synchronized(this) {
            telnyxClientWeakRef?.get() ?: TelnyxClient(context.applicationContext).also {
                telnyxClientWeakRef = WeakReference(it)
            }
        }
    }
}