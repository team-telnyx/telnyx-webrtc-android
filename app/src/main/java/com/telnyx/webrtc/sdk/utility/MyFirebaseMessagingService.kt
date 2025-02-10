/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utility

import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.telnyx.webrtc.sdk.utility.telecom.call.TelecomCallService
import org.json.JSONObject
import timber.log.Timber
import java.util.*


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
            return
        }

        //ToDo(Oliver Zimmerman) handle missed call with Telecom Service Later
        /*if (isMissedCall) {
            Timber.d("Missed Call")
            val serviceIntent = Intent(this, NotificationsService::class.java).apply {
                putExtra("action", NotificationsService.STOP_ACTION)
            }
            serviceIntent.setAction(NotificationsService.STOP_ACTION)
            startForegroundService(serviceIntent)
            return
        }*/

        val metadataObject = JSONObject(metadata)
        val callerDisplayName = metadataObject.getString("caller_name") ?: "Unknown Caller"
        val phoneNumber = metadataObject.getString("caller_number") ?: "Unknown Number"
        val telnyxCallIdString = metadataObject.getString("call_id")

        val incomingIntent = Intent(this, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_INCOMING_CALL
            putExtra(TelecomCallService.EXTRA_NAME, callerDisplayName)
            putExtra(TelecomCallService.EXTRA_URI, Uri.fromParts("tel", phoneNumber, null))
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, telnyxCallIdString)
        }
        startForegroundService(incomingIntent)
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


    companion object {
        const val MISSED_CALL = "Missed call!"
    }
}