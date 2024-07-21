package com.telnyx.webrtc.sdk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.google.gson.Gson
import com.telnyx.webrtc.sdk.di.AppModule
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.ui.MainActivity
import com.telnyx.webrtc.sdk.utility.MyFirebaseMessagingService
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketObserver
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import org.checkerframework.checker.units.qual.C
import timber.log.Timber

class NotificationsService : Service() {

    companion object {
        private const val CHANNEL_ID = "PHONE_CALL_NOTIFICATION_CHANNEL"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /* override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
     val metadata = intent?.getStringExtra("metadata")
     val telnyxPushMetadata = Gson().fromJson(metadata, PushMetaData::class.java)

     telnyxPushMetadata?.let {
         showNotification(telnyxPushMetadata,metadata!!)
     }
     return START_STICKY
 }*/

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val metadata = intent?.getStringExtra("metadata")
        val telnyxPushMetadata = Gson().fromJson(metadata, PushMetaData::class.java)
        val sharedPref = this.getSharedPreferences(
            AppModule.SHARED_PREFERENCES_KEY,
            Context.MODE_PRIVATE
        )
        val userManager = UserManager(sharedPref)
        telnyxPushMetadata?.let {
            showNotification(it)
            val loginConfig = CredentialConfig(
                userManager.sipUsername,
                userManager.sipPass,
                userManager.callerIdNumber,
                userManager.callerIdNumber,
                userManager.fcmToken,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),// or ringtone,
                R.raw.ringback_tone,
                LogLevel.ALL
            )
            App.telnyxClient.connect(txPushMetaData = metadata, credentialConfig = loginConfig, autoLogin = true)
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
                vibrationPattern =
                    longArrayOf(0, 1000, 500, 1000, 500)
                lightColor = Color.RED
                enableLights(true)
                enableVibration(true)
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun showNotification(txPushMetaData: PushMetaData) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val customSoundUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

        val rejectResultIntent = Intent(this, MainActivity::class.java)
        rejectResultIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        rejectResultIntent.action = Intent.ACTION_VIEW
        rejectResultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        rejectResultIntent.putExtra(
            MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
            MyFirebaseMessagingService.ACT_REJECT_CALL
        )
        val rejectPendingIntent = PendingIntent.getActivity(
            this,
            MyFirebaseMessagingService.REJECT_REQUEST_CODE,
            rejectResultIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val answerResultIntent = Intent(this, MainActivity::class.java)
        answerResultIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        answerResultIntent.action = Intent.ACTION_VIEW
        answerResultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        answerResultIntent.putExtra(
            MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
            MyFirebaseMessagingService.ACT_ANSWER_CALL
        )

        answerResultIntent.putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetaData.toJson())

        val answerPendingIntent = PendingIntent.getActivity(
            this,
            MyFirebaseMessagingService.ANSWER_REQUEST_CODE,
            answerResultIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        Timber.d("showNotification: ${txPushMetaData.toJson()}")


        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setContentTitle("Incoming Call")
            .setContentText("Incoming call from: ")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
            .setSound(customSoundUri)
            .addAction(R.drawable.ic_call_white,
                MyFirebaseMessagingService.ACT_ANSWER_CALL, answerPendingIntent)
            .addAction(R.drawable.ic_call_end_white,
                MyFirebaseMessagingService.ACT_REJECT_CALL, rejectPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pendingIntent, true)

        startForeground(NOTIFICATION_ID, builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
    }


    /*
    private fun showNotification(telnyxPushMetadata: PushMetaData,metaData: String) {

        val rejectResultIntent = Intent(this, MainActivity::class.java)
        rejectResultIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        rejectResultIntent.action = Intent.ACTION_VIEW
        rejectResultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        rejectResultIntent.putExtra(
            MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
            MyFirebaseMessagingService.ACT_REJECT_CALL
        )
        val rejectPendingIntent = PendingIntent.getActivity(
            this,
            MyFirebaseMessagingService.REJECT_REQUEST_CODE,
            rejectResultIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val answerResultIntent = Intent(this, MainActivity::class.java)
        answerResultIntent.addCategory(Intent.CATEGORY_LAUNCHER)
        answerResultIntent.action = Intent.ACTION_VIEW
        answerResultIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        answerResultIntent.putExtra(
            MyFirebaseMessagingService.EXT_KEY_DO_ACTION,
            MyFirebaseMessagingService.ACT_ANSWER_CALL
        )

        answerResultIntent.putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, metaData)

        val answerPendingIntent = PendingIntent.getActivity(
            this,
            MyFirebaseMessagingService.ANSWER_REQUEST_CODE,
            answerResultIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notificationSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val notificationBuilder = NotificationCompat.Builder(this,
            MyFirebaseMessagingService.TELNYX_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("Incoming Call")
            .setContentText(telnyxPushMetadata.callerName + " - " + telnyxPushMetadata.callerNumber)
            .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
            .addAction(R.drawable.ic_call_white,
                MyFirebaseMessagingService.ACT_ANSWER_CALL, answerPendingIntent)
            .addAction(R.drawable.ic_call_end_white,
                MyFirebaseMessagingService.ACT_REJECT_CALL, rejectPendingIntent)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSound(notificationSoundUri)



        startForeground(NOTIFICATION_ID, notificationBuilder.build())
    }

*/

}
