/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utility

import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.telnyx.webrtc.sdk.NotificationsService
import org.json.JSONObject
import timber.log.Timber


class MyFirebaseMessagingService : FirebaseMessagingService() {

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("Message Received From Firebase: ${remoteMessage.data}")
        Timber.d("Message Received From Firebase Priority: ${remoteMessage.priority}")
        Timber.d("Message Received From Firebase: ${remoteMessage.originalPriority}")

        val params = remoteMessage.data
        val objects = JSONObject(params as Map<*, *>)
        val metadata = objects.getString("metadata")
        val isMissedCall: Boolean = objects.getString("message").equals(MISSED_CALL)

        if (isMissedCall) {
            Timber.d("Missed Call")
            val serviceIntent = Intent(this, NotificationsService::class.java).apply {
                putExtra("action", NotificationsService.STOP_ACTION)
            }
            serviceIntent.setAction(NotificationsService.STOP_ACTION)
            startMessagingService(serviceIntent)
            return
        }

        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            putExtra("metadata", metadata)
        }
        startMessagingService(serviceIntent)
    }


    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the InstanceID token
     * is initially generated so this is where you would retrieve the token.
     */
    override fun onNewToken(token: String) {
        sendRegistrationToServer(token)
    }


    /**
     * Persist token to third-party servers.
     *
     * @param token The new token.
     */
    private fun sendRegistrationToServer(token: String) {
        Timber.d("sendRegistrationTokenToServer($token)")
    }

    private fun startMessagingService(serviceIntent: Intent) {
        startForegroundService(serviceIntent)
    }

    companion object {
        const val ANSWER_REQUEST_CODE = 0
        const val REJECT_REQUEST_CODE = 1

        const val TX_PUSH_METADATA = "tx_push_metadata"
        const val MISSED_CALL = "Missed call!"

        const val EXT_KEY_DO_ACTION = "ext_key_do_action"
        const val ACT_ANSWER_CALL = "answer"
        const val ACT_REJECT_CALL = "reject"
    }
}