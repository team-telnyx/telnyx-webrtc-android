/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * BroadcastReceiver for handling call notification actions
 */
class CallNotificationReceiver : BroadcastReceiver() {
    
    override fun onReceive(context: Context, intent: Intent?) {
        intent?.let {
            val notificationStateValue = it.getIntExtra(
                CallNotificationService.NOTIFICATION_ACTION,
                CallNotificationService.Companion.NotificationState.CANCEL.ordinal
            )
            
            val txPushMetadataJson = it.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
            val txPushMetadata = if (txPushMetadataJson != null) {
                Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
            } else {
                null
            }
            
            when (notificationStateValue) {
                CallNotificationService.Companion.NotificationState.ANSWER.ordinal -> {
                    Timber.d("Call answered from notification")
                    handleAnswerCall(context, txPushMetadata, notificationStateValue)
                }
                CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                    Timber.d("Call rejected from notification")
                    handleRejectCall(context, txPushMetadata, notificationStateValue)
                }
                else -> {
                    Timber.d("Call cancelled from notification")
                    handleCancelCall(context)
                }
            }
            
            // Cancel the notification
            CallNotificationService.cancelNotification(context)
        }
    }
    
    private fun handleAnswerCall(context: Context, txPushMetadata: PushMetaData?, notificationStateValue: Int) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Use the CallHandlerActivity to handle the call action
            // This helps work around Android's background activity launch restrictions
            launchCallHandlerActivity(context, txPushMetadata, notificationStateValue)
        }
    }
    
    private fun handleRejectCall(context: Context, txPushMetadata: PushMetaData?, notificationStateValue: Int) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Use the CallHandlerActivity to handle the call action
            // This helps work around Android's background activity launch restrictions
            launchCallHandlerActivity(context, txPushMetadata, notificationStateValue)
        }
    }
    
    private fun handleCancelCall(context: Context) {
        // Handle call cancellation
        val currentCall = TelnyxCommon.getInstance().currentCall
        currentCall?.endCall(currentCall.callId)
        
        // Start the CallHandlerService to handle the call cancellation
        val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
            action = CallHandlerService.ACTION_HANDLE_CALL
            putExtra(
                CallHandlerService.EXTRA_NOTIFICATION_ACTION,
                CallNotificationService.Companion.NotificationState.CANCEL.ordinal
            )
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to start CallHandlerService")
        }
    }
    
    private fun launchCallHandlerActivity(context: Context, txPushMetadata: PushMetaData, notificationStateValue: Int) {
        try {
            // Launch the CallHandlerActivity
            val intent = Intent(context, CallHandlerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(CallNotificationService.NOTIFICATION_ACTION, notificationStateValue)
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch CallHandlerActivity")
            
            // Fallback to direct activity launch if CallHandlerActivity fails
            val targetActivityClass = getTargetActivityClass(context)
            targetActivityClass?.let { activityClass ->
                try {
                    val action = if (notificationStateValue == CallNotificationService.Companion.NotificationState.ANSWER.ordinal) {
                        MyFirebaseMessagingService.ACT_ANSWER_CALL
                    } else {
                        MyFirebaseMessagingService.ACT_REJECT_CALL
                    }
                    
                    val mainIntent = Intent(context, activityClass).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        this.action = Intent.ACTION_VIEW
                        putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, action)
                        putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                    }
                    context.startActivity(mainIntent)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to launch main activity directly")
                    
                    // Last resort: try to start the service
                    val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                        this.action = CallHandlerService.ACTION_HANDLE_CALL
                        putExtra(CallHandlerService.EXTRA_NOTIFICATION_ACTION, notificationStateValue)
                        putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                    }
                    
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start CallHandlerService")
                    }
                }
            }
        }
    }
    
    private fun getTargetActivityClass(context: Context): Class<*>? {
        return try {
            val serviceInfo = context.packageManager.getServiceInfo(
                ComponentName(context, NotificationsService::class.java),
                android.content.pm.PackageManager.GET_META_DATA
            )
            val activityClassName = serviceInfo.metaData.getString("activity_class_name") ?: return null
            Class.forName(activityClassName)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get target activity class")
            null
        }
    }
}
