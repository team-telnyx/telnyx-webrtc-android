package com.telnyx.webrtc.common.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Resources.NotFoundException
import android.graphics.Color
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.telnyx.webrtc.common.R
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber


/**
 * Legacy notification service for handling incoming calls
 * This service is maintained for backward compatibility
 * New implementations should use CallNotificationService
 */
class LegacyCallNotificationService : Service() {

    companion object {
        private const val CHANNEL_ID = "PHONE_CALL_NOTIFICATION_CHANNEL"
        private const val NOTIFICATION_ID = 1
        const val STOP_ACTION = "STOP_ACTION"
    }

    private var callNotificationService: CallNotificationService? = null
    private var ringtone: Ringtone? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Initialize the new CallNotificationService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                callNotificationService =
                    CallNotificationService(this, CallNotificationReceiver::class.java)
            } catch (e: ClassNotFoundException) {
                Timber.e(e, "Failed to initialize CallNotificationService: Class not found")
            } catch (e: NoSuchMethodException) {
                Timber.e(e, "Failed to initialize CallNotificationService: Method not found")
            }
        }
    }

    private fun playPushRingTone() {
        try {
            val notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(applicationContext, notification)
            ringtone?.play()
        } catch (e: NotFoundException) {
            Timber.e("playPushRingTone: $e")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopAction = intent?.action
        if (stopAction != null && stopAction == STOP_ACTION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                ringtone?.stop()
            } else {
                stopForeground(true)
            }

            // Also cancel any CallNotificationService notifications
            callNotificationService?.cancelNotification()

            return START_NOT_STICKY
        }

        // For foreground services, we must call startForeground() immediately
        // Start with a basic notification that will be updated later
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundImmediately()
        }

        val metadata = intent?.getStringExtra("metadata")
        val telnyxPushMetadata = try {
            if (metadata != null) {
                Gson().fromJson(metadata, PushMetaData::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse push metadata")
            null
        }

        telnyxPushMetadata?.let {
            // Try to use the new CallNotificationService if available
            if (callNotificationService != null && useCallStyleNotification()) {
                try {
                    callNotificationService?.showIncomingCallNotification(it)
                    playPushRingTone()
                    return START_STICKY
                } catch (e: IllegalStateException) {
                    Timber.e(
                        e,
                        "Error showing call notification with CallStyle, falling back to legacy"
                    )
                }
            }

            // Fallback to legacy notification
            showNotification(it)
            playPushRingTone()
        } ?: run {
            // If metadata is null or invalid, we still need to maintain the foreground service
            // with a basic notification to prevent the exception
            Timber.w("No valid metadata found, maintaining basic foreground notification")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Phone Call Notifications"
            val description = "Notifications for incoming phone calls"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            channel.description = description

            val notificationManager = getSystemService(NotificationManager::class.java)
            channel.apply {
                lightColor = Color.RED
                enableLights(true)
                enableVibration(true)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(txPushMetaData: PushMetaData) {
        val targetActivityClass = Class.forName(getActivityClassName())

        val intent = Intent(this, targetActivityClass).apply {
            action = Intent.ACTION_VIEW
            putExtra(
                MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
                MyFirebaseMessagingService.ACT_OPEN_TO_REPLY
            )
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())
        }

        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(
                this,
                MyFirebaseMessagingService.OPEN_TO_REPLY_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        val customSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        // Reject call intent - use broadcast receiver instead of opening main activity
        val rejectResultIntent = Intent(this, CallNotificationReceiver::class.java).apply {
            putExtra(CallNotificationService.NOTIFICATION_ACTION, CallNotificationService.Companion.NotificationState.REJECT.ordinal)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())
        }
        val rejectPendingIntent = PendingIntent.getBroadcast(
            this,
            MyFirebaseMessagingService.REJECT_REQUEST_CODE,
            rejectResultIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val answerResultIntent = Intent(this, Class.forName(getActivityClassName()))
        answerResultIntent.setAction(Intent.ACTION_VIEW)

        answerResultIntent.putExtra(
            MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
            MyFirebaseMessagingService.ACT_ANSWER_CALL
        )

        answerResultIntent.putExtra(
            MyFirebaseMessagingService.TX_PUSH_METADATA,
            txPushMetaData.toJson()
        )

        val answerPendingIntent = PendingIntent.getActivity(
            this,
            MyFirebaseMessagingService.ANSWER_REQUEST_CODE,
            answerResultIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        Timber.d("showNotification: ${txPushMetaData.toJson()}")

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setContentTitle("Incoming Call : ${txPushMetaData.callerName}")
            .setContentText("Incoming call from: ${txPushMetaData.callerNumber} ")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setSound(customSoundUri)
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
            .setFullScreenIntent(pendingIntent, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                builder.build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, builder.build())
        }
    }

    private fun getActivityClassName(): String {
        val ai = packageManager.getServiceInfo(
            ComponentName(this, LegacyCallNotificationService::class.java),
            PackageManager.GET_META_DATA
        )
        return ai.metaData.getString("activity_class_name") ?: ""
    }

    /**
     * Determine if we should use the new CallStyle notification
     * This can be extended to check for device capabilities or preferences
     */
    private fun useCallStyleNotification(): Boolean {
        // For now, use CallStyle on Android 12 (API 31) and above
        // This can be adjusted based on testing results
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }

    /**
     * Start the service as foreground immediately with a basic notification
     * This prevents ForegroundServiceDidNotStartInTimeException
     */
    private fun startForegroundImmediately() {
        val basicNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setContentTitle("Incoming Call")
            .setContentText("Processing call...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                basicNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            )
        } else {
            startForeground(NOTIFICATION_ID, basicNotification)
        }
    }
}
