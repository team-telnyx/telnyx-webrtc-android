/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.getSystemService
import com.telnyx.webrtc.common.R
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Service for handling call notifications using the modern CallStyle API
 */
class CallNotificationService @RequiresApi(Build.VERSION_CODES.O) constructor(
    private val context: Context,
    private val notificationReceiverClass: Class<*>
) {

    companion object {
        const val CHANNEL_ID = "telnyx_call_notification_channel"
        const val CHANNEL_ONGOING_ID = "telnyx_call_ongoing_channel"
        const val NOTIFICATION_ID = 1234
        const val NOTIFICATION_ACTION = "NOTIFICATION_ACTION"

        enum class NotificationState(val value: Int) {
            ANSWER(0),
            REJECT(1),
            CANCEL(2);
        }

        /**
         * Cancel notification and dismiss from system UI
         */
        fun cancelNotification(context: Context) {
            context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
        }
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannels()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannels() {
        val managerCompat = NotificationManagerCompat.from(context)

        // Create incoming call channel
        val incomingCallChannel = NotificationChannel(
            CHANNEL_ID,
            "Incoming Call Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
            action = Intent.ACTION_VIEW
            putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_OPEN_TO_REPLY)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context, MyFirebaseMessagingService.OPEN_TO_REPLY_REQUEST_CODE, fullScreenIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Answer call intent
        val answerResultIntent = Intent(context, targetActivityClass).apply {
            action = Intent.ACTION_VIEW
            putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_ANSWER_CALL)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context, MyFirebaseMessagingService.ANSWER_REQUEST_CODE, answerResultIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Reject call intent - use broadcast receiver instead of opening main activity
        val rejectResultIntent = Intent(context, notificationReceiverClass).apply {
            putExtra(NOTIFICATION_ACTION, NotificationState.REJECT.ordinal)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            context, MyFirebaseMessagingService.REJECT_REQUEST_CODE, rejectResultIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create caller person
        val caller = Person.Builder()
            .setName(txPushMetaData.callerName)
            .setImportant(true)
            .build()

        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (keyguardManager.isKeyguardLocked) {
            // The screen is locked—build a notification without a full-screen intent.
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_contact_phone)
                .setContentTitle("Incoming Call : ${txPushMetaData.callerName}")
                .setContentText("Incoming call from: ${txPushMetaData.callerNumber} ")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setContentIntent(fullScreenPendingIntent)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                .addAction(
                    R.drawable.ic_call_white,
                    MyFirebaseMessagingService.ACT_ANSWER_CALL, answerPendingIntent
                )
                .addAction(
                    R.drawable.ic_call_end_white,
                    MyFirebaseMessagingService.ACT_REJECT_CALL, rejectPendingIntent
                )
                .setOngoing(true)
                .setAutoCancel(false)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()
        } else {
            // The screen is unlocked. Build a call style notification with a full-screen intent.
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_contact_phone)
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
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .build()
        }
    }

    /**
     * Create an ongoing call notification
     * This is used by the CallForegroundService to show a persistent notification
     * during active calls when the app is minimized
     */
    fun createOngoingCallNotification(txPushMetaData: PushMetaData): Notification {
        // Get the target activity class
        val activityClassName = getActivityClassName()
        val targetActivityClass = try {
            if (activityClassName.isNotEmpty()) {
                Class.forName(activityClassName)
            } else {
                null
            }
        } catch (e: ClassNotFoundException) {
            Timber.e(e, "Failed to get target activity class")
            null
        }

        // Intent for full screen activity
        val fullScreenIntent = if (targetActivityClass != null) {
            Intent(context, targetActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            }
        } else {
            // Fallback to a generic launcher intent if we can't get the specific activity
            Intent().apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
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
            MyFirebaseMessagingService.END_CALL_REQUEST_CODE,
            endCallIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Create caller person
        val caller = Person.Builder()
            .setName(txPushMetaData.callerName)
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
        return try {
            val ai = context.packageManager.getServiceInfo(
                ComponentName(context, LegacyCallNotificationService::class.java),
                PackageManager.GET_META_DATA
            )
            ai.metaData?.getString("activity_class_name") ?: ""
        } catch (e: PackageManager.NameNotFoundException) {
            Timber.e(e, "Failed to get activity class name")
            ""
        }
    }
}
