package com.telnyx.webrtc.sdk.utilities.fcm

import android.content.Context
import com.google.firebase.messaging.RemoteMessage
import timber.log.Timber

object TelnyxFcm {
    fun processPushMessage(context: Context, remoteMessage: RemoteMessage) {
        // Not getting messages here? See why this may be: https://goo.gl/39bRNJ
        Timber.d( " Message From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        if (remoteMessage.data.isNotEmpty()) {
            Timber.d("Message data payload: ${remoteMessage.data}")

            if (/* Check if data needs to be processed by long running job */ true) {
                // For long-running tasks (10 seconds or more) use WorkManager.
               // scheduleJob()
            } else {
                // Handle message within 10 seconds
              //  handleNow()
            }
        }

        // Check if message contains a notification payload.
        remoteMessage.notification?.let {
            Timber.d("Message Notification Body: ${it.body}")
        }

    }

    fun sendRegistrationToServer(context: Context, token: String) {
        //ToDo
    }
}