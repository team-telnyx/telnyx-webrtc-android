/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.telnyx.webrtc.sdk.model.PushMetaData
import org.json.JSONObject
import timber.log.Timber


class MyFirebaseMessagingService : FirebaseMessagingService() {

    private var callNotificationService: CallNotificationService? = null

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Timber.d("Message Received From Firebase: ${remoteMessage.data}")
        Timber.d("Message Received From Firebase Priority: ${remoteMessage.priority}")
        Timber.d("Message Received From Firebase: ${remoteMessage.originalPriority}")

        val params = remoteMessage.data
        val objects = JSONObject(params as Map<*, *>)
        val metadata = objects.getString("metadata")
        val isMissedCall: Boolean = objects.getString("message").equals(MISSED_CALL)

        if(isMissedCall){
            Timber.d("Missed Call")
            val serviceIntent = Intent(this, LegacyCallNotificationService::class.java).apply {
                putExtra("action", LegacyCallNotificationService.STOP_ACTION)
            }
            serviceIntent.setAction(LegacyCallNotificationService.STOP_ACTION)
            startMessagingService(serviceIntent)
            return
        }

        // Initialize CallNotificationService if needed
        if (callNotificationService == null) {
            callNotificationService = CallNotificationService(this, CallNotificationReceiver::class.java)
        }

        // Try to use the new CallNotificationService if available
        try {
            val telnyxPushMetadata = Gson().fromJson(metadata, PushMetaData::class.java)
            telnyxPushMetadata?.let {
                // Show incoming call notification using CallStyle
                callNotificationService?.showIncomingCallNotification(it)
                return
            }
        } catch (e: JsonSyntaxException) {
            Timber.e(e, "Error parsing push metadata JSON, falling back to legacy notification")
        }

        // Fallback to legacy notification service
        val serviceIntent = Intent(this, LegacyCallNotificationService::class.java).apply {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        const val ANSWER_REQUEST_CODE = 0
        const val REJECT_REQUEST_CODE = 1
        const val OPEN_TO_REPLY_REQUEST_CODE = 2
        const val END_CALL_REQUEST_CODE = 1202

        const val TX_PUSH_METADATA = "tx_push_metadata"
        const val MISSED_CALL = "Missed call!"

        const val EXT_KEY_DO_ACTION = "ext_key_do_action"
        const val ACT_ANSWER_CALL = "answer"
        const val ACT_REJECT_CALL = "reject"
        const val ACT_OPEN_TO_REPLY = "open_to_reply"
    }
}