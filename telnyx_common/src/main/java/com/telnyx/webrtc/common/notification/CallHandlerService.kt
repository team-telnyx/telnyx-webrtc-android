/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Service to handle call actions from the background
 * This service is used to handle call actions when the app is in the background or killed state
 * It helps work around Android's background activity launch restrictions
 */
class CallHandlerService : Service() {

    companion object {
        const val ACTION_HANDLE_CALL = "ACTION_HANDLE_CALL"
        const val EXTRA_NOTIFICATION_ACTION = "EXTRA_NOTIFICATION_ACTION"
    }

    private var callNotificationService: CallNotificationService? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("CallHandlerService created")
        
        // Initialize the CallNotificationService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callNotificationService = CallNotificationService(this, CallNotificationReceiver::class.java)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("CallHandlerService onStartCommand: ${intent?.action}")
        
        if (intent?.action == ACTION_HANDLE_CALL) {
            // Get the notification action and metadata
            val notificationStateValue = intent.getIntExtra(
                EXTRA_NOTIFICATION_ACTION,
                CallNotificationService.Companion.NotificationState.CANCEL.ordinal
            )
            
            val txPushMetadataJson = intent.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
            val txPushMetadata = if (txPushMetadataJson != null) {
                Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
            } else {
                null
            }
            
            // Start as a foreground service with a high-priority notification
            startForeground()
            
            // Handle the action
            when (notificationStateValue) {
                CallNotificationService.Companion.NotificationState.ANSWER.ordinal -> {
                    Timber.d("Handling answer call action")
                    handleAnswerCall(txPushMetadata)
                }
                CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                    Timber.d("Handling reject call action")
                    handleRejectCall(txPushMetadata)
                }
                else -> {
                    Timber.d("Handling cancel call action")
                    handleCancelCall()
                }
            }
            
            // Cancel the notification
            CallNotificationService.cancelNotification(this)
        }
        
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("CallHandlerService destroyed")
    }

    private fun startForeground() {
        // Create a simple notification to keep the service in the foreground
        callNotificationService?.let { service ->
            val metadata = PushMetaData(
                callerName = "Handling Call",
                callerNumber = "",
                callId = ""
            )
            val notification = service.createOngoingCallNotification(metadata)
            
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        CallNotificationService.NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    )
                } else {
                    startForeground(CallNotificationService.NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start foreground service")
                startForeground(CallNotificationService.NOTIFICATION_ID, notification)
            }
        }
    }

    private fun handleAnswerCall(txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Launch the main activity to handle the call
            launchMainActivity(MyFirebaseMessagingService.ACT_ANSWER_CALL, txPushMetadata)
        }
        
        // Stop the service after handling the action
        stopSelf()
    }

    private fun handleRejectCall(txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Launch the main activity to handle the call rejection
            launchMainActivity(MyFirebaseMessagingService.ACT_REJECT_CALL, txPushMetadata)
        }
        
        // Stop the service after handling the action
        stopSelf()
    }

    private fun handleCancelCall() {
        // Handle call cancellation
        val currentCall = TelnyxCommon.getInstance().currentCall
        currentCall?.endCall(currentCall.callId)
        
        // Stop the service after handling the action
        stopSelf()
    }

    private fun launchMainActivity(action: String, txPushMetadata: PushMetaData) {
        val targetActivityClass = getTargetActivityClass()
        targetActivityClass?.let { activityClass ->
            val intent = Intent(this, activityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                this.action = Intent.ACTION_VIEW
                putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, action)
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            startActivity(intent)
        }
    }

    private fun getTargetActivityClass(): Class<*>? {
        return try {
            val serviceInfo = packageManager.getServiceInfo(
                ComponentName(this, NotificationsService::class.java),
                PackageManager.GET_META_DATA
            )
            val activityClassName = serviceInfo.metaData.getString("activity_class_name") ?: return null
            Class.forName(activityClassName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get target activity class")
            null
        }
    }
}