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
import com.telnyx.webrtc.common.service.CallHandlerService
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
                    handleAnswerCall(context, txPushMetadata)
                }
                CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                    Timber.d("Call rejected from notification")
                    handleRejectCall(context, txPushMetadata)
                }
                else -> {
                    Timber.d("Call cancelled from notification")
                    handleCancelCall()
                }
            }
            
            // Cancel the notification
            CallNotificationService.cancelNotification(context)
        }
    }
    
    private fun handleAnswerCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Start the CallHandlerService to handle the call
            val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                action = CallHandlerService.ACTION_ANSWER_CALL
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Also start the CallHandlerActivity as a fallback
            val handlerIntent = Intent(context, CallHandlerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_ANSWER_CALL)
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            try {
                context.startActivity(handlerIntent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start CallHandlerActivity")
            }
        }
    }
    
    private fun handleRejectCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Start the CallHandlerService to handle the call rejection
            val serviceIntent = Intent(context, CallHandlerService::class.java).apply {
                action = CallHandlerService.ACTION_REJECT_CALL
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            
            // Also start the CallHandlerActivity as a fallback
            val handlerIntent = Intent(context, CallHandlerActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_REJECT_CALL)
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
            }
            try {
                context.startActivity(handlerIntent)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start CallHandlerActivity")
            }
        }
    }
    
    private fun handleCancelCall() {
        // Handle call cancellation
        val currentCall = TelnyxCommon.getInstance().currentCall
        currentCall?.endCall(currentCall.callId)
    }
}
