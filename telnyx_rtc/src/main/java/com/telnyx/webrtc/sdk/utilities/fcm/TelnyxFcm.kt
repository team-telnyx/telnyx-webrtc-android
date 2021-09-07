/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities.fcm

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import timber.log.Timber

object TelnyxFcm {
    fun processPushMessage(context: Context, remoteMessage: RemoteMessage) {
        Timber.d("Message received from FCM to be processed by Telnyx: ${remoteMessage.data}")
    }
}