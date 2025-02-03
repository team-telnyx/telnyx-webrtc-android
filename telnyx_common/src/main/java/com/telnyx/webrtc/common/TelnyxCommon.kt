package com.telnyx.webrtc.common

import android.content.Context
import com.telnyx.webrtc.sdk.TelnyxClient

internal object TelnyxCommon {
    @Volatile
    private var telnyxClient: TelnyxClient? = null

    fun getTelnyxClient(context: Context): TelnyxClient {
        return telnyxClient ?: synchronized(this) {
            telnyxClient ?: TelnyxClient(context.applicationContext).also { telnyxClient = it }
        }
    }
}
