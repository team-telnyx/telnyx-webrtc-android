/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utility

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.TelnyxPushNotification
import com.telnyx.webrtc.sdk.ui.MainActivity
import com.telnyx.webrtc.sdk.utilities.fcm.TelnyxFcm
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
        TelnyxFcm.processPushMessage(this, remoteMessage)

       /* val params = remoteMessage.data
        val objects = JSONObject(params as Map<*, *>)
        val metadata = objects.getString("metadata")
        Timber.d("X PUSH: $metadata")*/

       /* val metaDataObject = JSONObject(metadata)
        metaDataObject.getString("caller_name")

        val gson = Gson()
        val telnyxPushMetadata = gson.fromJson(metadata, PushMetaData::class.java)
        Timber.d("X PUSH: ${remoteMessage.data.toString()}")
       // val telnyxPush = gson.fromJson(remoteMessage.data.toString(), TelnyxPushNotification::class.java)
        Timber.d("X PUSH: ${telnyxPushMetadata}")*/


        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notificationID = Random().nextInt(3000)

        /*
        Apps targeting SDK 26 or above (Android O) must implement notification channels and add its notifications
        to at least one of them.
      */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setupChannels(notificationManager)
        }

        val answerResultIntent = Intent(this, MainActivity::class.java)
        val answerPendingIntent = PendingIntent.getActivity(this, ANSWER_REQUEST_CODE, answerResultIntent, PendingIntent.FLAG_UPDATE_CURRENT)

        val notificationSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val notificationBuilder = NotificationCompat.Builder(this, TELNYX_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(remoteMessage.data["title"])
            .setContentText(remoteMessage.data["message"])
            .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
            .addAction(R.drawable.ic_call_black, ACT_ANSWER_CALL, answerPendingIntent)
            .setAutoCancel(true)
            .setSound(notificationSoundUri)

        notificationManager.notify(notificationID, notificationBuilder.build())
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private fun setupChannels(notificationManager: NotificationManager?) {
        val adminChannelName = "New notification"
        val adminChannelDescription = "Device to device notification"

        val adminChannel = NotificationChannel(
            TELNYX_CHANNEL_ID,
            adminChannelName,
            NotificationManager.IMPORTANCE_HIGH
        )
        adminChannel.description = adminChannelDescription
        adminChannel.enableLights(true)
        adminChannel.lightColor = Color.RED
        adminChannel.enableVibration(true)
        notificationManager?.createNotificationChannel(adminChannel)
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
        Timber.d( "sendRegistrationTokenToServer($token)")
    }

    /**
     * Create and show a simple notification containing the received FCM message.
     *
     * @param messageBody FCM message body received.
     */
    private fun sendNotification(messageBody: String) {
    }

    companion object {
        private const val TELNYX_CHANNEL_ID = "telnyx_channel"
        private const val ANSWER_REQUEST_CODE = 0
        const val ACT_ANSWER_CALL = "open"
    }
}