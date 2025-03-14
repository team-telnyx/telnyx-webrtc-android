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
                    handleAnswerCall(context, txPushMetadata)
                }
                CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                    Timber.d("Call rejected from notification")
                    handleRejectCall(context, txPushMetadata)
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
    
    private fun handleAnswerCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Start the main activity to handle the call
            val targetActivityClass = getTargetActivityClass(context)
            targetActivityClass?.let { activityClass ->
                val intent = Intent(context, activityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    action = Intent.ACTION_VIEW
                    putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_ANSWER_CALL)
                    putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                }
                context.startActivity(intent)
            }
        }
    }
    
    private fun handleRejectCall(context: Context, txPushMetadata: PushMetaData?) {
        txPushMetadata?.let {
            // Set handling push to true to prevent disconnect
            TelnyxCommon.getInstance().setHandlingPush(true)
            
            // Start the main activity to handle the call rejection
            val targetActivityClass = getTargetActivityClass(context)
            targetActivityClass?.let { activityClass ->
                val intent = Intent(context, activityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    action = Intent.ACTION_VIEW
                    putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_REJECT_CALL)
                    putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadata.toJson())
                }
                context.startActivity(intent)
            }
        }
    }
    
    private fun handleCancelCall(context: Context) {
        // Handle call cancellation
        val currentCall = TelnyxCommon.getInstance().currentCall
        if (currentCall != null) {
            // If there's an active call, start the main activity to handle ending the call
            val targetActivityClass = getTargetActivityClass(context)
            targetActivityClass?.let { activityClass ->
                val intent = Intent(context, activityClass).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(intent)
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