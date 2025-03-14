/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.getSystemService
import com.google.gson.Gson
import com.telnyx.webrtc.common.R
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Service for handling call notifications using the modern CallStyle API
 */
class CallNotificationService {

    companion object {
        private const val CHANNEL_ID = "telnyx_call_notification_channel"
        private const val CHANNEL_ONGOING_ID = "telnyx_call_ongoing_channel"
        private const val NOTIFICATION_ID = 1234
        const val NOTIFICATION_ACTION = "NOTIFICATION_ACTION"

        enum class NotificationState(val value: Int) {
            ANSWER(0),
            REJECT(1),
            CANCEL(2);

            companion object {
                fun valueOf(value: Int) = values().find { it.value == value }
            }
        }

        /**
         * Cancel notification and dismiss from system UI
         */
        fun cancelNotification(context: Context) {
            context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
        }
    }

    private val notificationManager: NotificationManager
    private val context: Context
    private val notificationReceiverClass: Class<*>

    constructor(context: Context, notificationReceiverClass: Class<*>) {
        this.context = context
        this.notificationReceiverClass = notificationReceiverClass
        this.notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val managerCompat = NotificationManagerCompat.from(context)
        
        // Create incoming call channel
        val incomingCallChannel = NotificationChannel(
            CHANNEL_ID,
            "Incoming Call Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setLegacyStreamType(AudioManager.STREAM_RING)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build()
            )
            enableLights(true)
            enableVibration(true)
        }
        
        // Create ongoing call channel
        val ongoingCallChannel = NotificationChannel(
            CHANNEL_ONGOING_ID,
            "Ongoing Call Notifications",
            NotificationManager.IMPORTANCE_LOW
        )
        
        managerCompat.createNotificationChannels(listOf(incomingCallChannel, ongoingCallChannel))
    }

    /**
     * Show incoming call notification
     */
    fun showIncomingCallNotification(txPushMetaData: PushMetaData) {
        Timber.d("Showing incoming call notification: ${txPushMetaData.toJson()}")
        notificationManager.notify(NOTIFICATION_ID, createIncomingCallNotification(txPushMetaData))
    }

    /**
     * Show ongoing call notification
     */
    fun showOngoingCallNotification(txPushMetaData: PushMetaData) {
        Timber.d("Showing ongoing call notification: ${txPushMetaData.toJson()}")
        notificationManager.notify(NOTIFICATION_ID, createOngoingCallNotification(txPushMetaData))
    }

    /**
     * Cancel notification
     */
    fun cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun createIncomingCallNotification(txPushMetaData: PushMetaData): Notification {
        val targetActivityClass = Class.forName(getActivityClassName())
        
        // Intent for full screen activity
        val fullScreenIntent = Intent(context, targetActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent, PendingIntent.FLAG_MUTABLE
        )
        
        // Answer call intent
        val answerIntent = Intent(context, notificationReceiverClass)
        answerIntent.putExtra(NOTIFICATION_ACTION, NotificationState.ANSWER.ordinal)
        answerIntent.putExtra(
            MyFirebaseMessagingService.TX_PUSH_METADATA,
            txPushMetaData.toJson()
        )
        val answerPendingIntent = PendingIntent.getBroadcast(
            context, 
            MyFirebaseMessagingService.ANSWER_REQUEST_CODE,
            answerIntent, 
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Reject call intent
        val rejectIntent = Intent(context, notificationReceiverClass)
        rejectIntent.putExtra(NOTIFICATION_ACTION, NotificationState.REJECT.ordinal)
        rejectIntent.putExtra(
            MyFirebaseMessagingService.TX_PUSH_METADATA,
            txPushMetaData.toJson()
        )
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context, 
            MyFirebaseMessagingService.REJECT_REQUEST_CODE,
            rejectIntent, 
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create caller person
        val caller = Person.Builder()
            .setName(txPushMetaData.callerName ?: "Unknown Caller")
            .setImportant(true)
            .build()
        
        // Build notification
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    caller,
                    rejectPendingIntent,
                    answerPendingIntent
                )
            )
            .build()
    }

    private fun createOngoingCallNotification(txPushMetaData: PushMetaData): Notification {
        val targetActivityClass = Class.forName(getActivityClassName())
        
        // Intent for full screen activity
        val fullScreenIntent = Intent(context, targetActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, 0, fullScreenIntent, PendingIntent.FLAG_MUTABLE
        )
        
        // End call intent
        val endCallIntent = Intent(context, notificationReceiverClass)
        endCallIntent.putExtra(NOTIFICATION_ACTION, NotificationState.CANCEL.ordinal)
        endCallIntent.putExtra(
            MyFirebaseMessagingService.TX_PUSH_METADATA,
            txPushMetaData.toJson()
        )
        val endCallPendingIntent = PendingIntent.getBroadcast(
            context, 
            1202,
            endCallIntent, 
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Create caller person
        val caller = Person.Builder()
            .setName(txPushMetaData.callerName ?: "Unknown Caller")
            .setImportant(true)
            .build()
        
        // Build notification
        return NotificationCompat.Builder(context, CHANNEL_ONGOING_ID)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setFullScreenIntent(fullScreenPendingIntent, false)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setStyle(
                NotificationCompat.CallStyle.forOngoingCall(
                    caller,
                    endCallPendingIntent
                )
            )
            .build()
    }

    private fun getActivityClassName(): String {
        val ai = context.packageManager.getServiceInfo(
            ComponentName(context, NotificationsService::class.java),
            PackageManager.GET_META_DATA
        )
        return ai.metaData.getString("activity_class_name") ?: ""
    }
}