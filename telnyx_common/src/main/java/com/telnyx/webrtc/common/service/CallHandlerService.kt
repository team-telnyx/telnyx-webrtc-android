/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.telnyx.webrtc.common.R
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.notification.CallHandlerActivity
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.NotificationsService
import com.telnyx.webrtc.sdk.model.PushMetaData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Service to handle call actions from the background
 * This service is used to handle call actions when the app is in the background
 * and to launch the main activity to handle the call
 */
class CallHandlerService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 9876
        private const val CHANNEL_ID = "telnyx_call_handler_channel"
        
        const val ACTION_ANSWER_CALL = "ACTION_ANSWER_CALL"
        const val ACTION_REJECT_CALL = "ACTION_REJECT_CALL"
    }
    
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private val viewModel = TelnyxViewModel()
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // Start as a foreground service with a notification
        startForeground(NOTIFICATION_ID, createNotification("Processing call action..."))
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Timber.e("Null intent received in CallHandlerService")
            stopSelf()
            return START_NOT_STICKY
        }
        
        val action = intent.action
        val txPushMetadataJson = intent.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
        
        if (action == null || txPushMetadataJson == null) {
            Timber.e("Missing action or push metadata in CallHandlerService")
            stopSelf()
            return START_NOT_STICKY
        }
        
        val txPushMetadata = try {
            Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse push metadata")
            stopSelf()
            return START_NOT_STICKY
        }
        
        // Handle the action
        when (action) {
            ACTION_ANSWER_CALL -> {
                handleAnswerCall(txPushMetadata)
            }
            ACTION_REJECT_CALL -> {
                handleRejectCall(txPushMetadata)
            }
            else -> {
                Timber.e("Unknown action: $action")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
    
    private fun handleAnswerCall(txPushMetadata: PushMetaData) {
        Timber.d("Handling answer call in service")
        
        // Set handling push to true to prevent disconnect
        TelnyxCommon.getInstance().setHandlingPush(true)
        
        // Process the call in a coroutine
        serviceScope.launch {
            try {
                // Answer the call
                viewModel.answerIncomingPushCall(this@CallHandlerService, txPushMetadata.toJson())
                
                // Launch the main activity
                launchMainActivity(MyFirebaseMessagingService.ACT_ANSWER_CALL, txPushMetadata)
            } catch (e: Exception) {
                Timber.e(e, "Error answering call")
            } finally {
                // Stop the service
                stopSelf()
            }
        }
    }
    
    private fun handleRejectCall(txPushMetadata: PushMetaData) {
        Timber.d("Handling reject call in service")
        
        // Set handling push to true to prevent disconnect
        TelnyxCommon.getInstance().setHandlingPush(true)
        
        // Process the call in a coroutine
        serviceScope.launch {
            try {
                // Reject the call
                viewModel.rejectIncomingPushCall(this@CallHandlerService, txPushMetadata.toJson())
                
                // Launch the main activity
                launchMainActivity(MyFirebaseMessagingService.ACT_REJECT_CALL, txPushMetadata)
            } catch (e: Exception) {
                Timber.e(e, "Error rejecting call")
            } finally {
                // Stop the service
                stopSelf()
            }
        }
    }
    
    private fun launchMainActivity(action: String, txPushMetadata: PushMetaData) {
        try {
            // Get the target activity class
            val targetActivityClass = getTargetActivityClass()
            if (targetActivityClass == null) {
                Timber.e("Could not determine target activity class")
                return
            }
            
            // Create intent for the main activity
            val mainActivityIntent = Intent(this, targetActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                this.action = Intent.ACTION_VIEW
                putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, action)
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            
            // Start the main activity
            startActivity(mainActivityIntent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch main activity")
            
            // Try with the CallHandlerActivity as a fallback
            try {
                val handlerIntent = Intent(this, CallHandlerActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, action)
                    putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                }
                startActivity(handlerIntent)
            } catch (e2: Exception) {
                Timber.e(e2, "Failed to start CallHandlerActivity")
            }
        }
    }
    
    private fun getTargetActivityClass(): Class<*>? {
        return try {
            val serviceInfo = packageManager.getServiceInfo(
                ComponentName(this, NotificationsService::class.java),
                PackageManager.GET_META_DATA
            )
            val activityClassName = serviceInfo.metaData?.getString("activity_class_name") ?: return null
            Class.forName(activityClassName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get target activity class")
            null
        }
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call Handler",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Used to handle call actions in the background"
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String): Notification {
        // Create a pending intent for the CallHandlerActivity
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, CallHandlerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Processing Call")
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_stat_contact_phone)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
    }
}