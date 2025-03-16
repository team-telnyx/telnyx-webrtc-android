/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Transparent activity to handle call actions when the app is in the background
 * This activity is used as a trampoline to handle call actions and then launch the main activity
 * It helps work around Android's background activity launch restrictions
 */
class CallHandlerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Process the intent immediately
        handleIntent(intent)
        
        // Finish this transparent activity
        finish()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            Timber.e("CallHandlerActivity received null intent")
            return
        }
        
        Timber.d("CallHandlerActivity handling intent: ${intent.action}")
        
        // Get the action and metadata from the intent
        val notificationStateValue = intent.getIntExtra(
            CallNotificationService.NOTIFICATION_ACTION,
            CallNotificationService.Companion.NotificationState.CANCEL.ordinal
        )
        
        val txPushMetadataJson = intent.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
        val txPushMetadata = if (txPushMetadataJson != null) {
            Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
        } else {
            null
        }
        
        // Set handling push to true to prevent disconnect
        TelnyxCommon.getInstance().setHandlingPush(true)
        
        // Start the CallHandlerService to handle the action
        val serviceIntent = Intent(this, CallHandlerService::class.java).apply {
            action = CallHandlerService.ACTION_HANDLE_CALL
            putExtra(CallHandlerService.EXTRA_NOTIFICATION_ACTION, notificationStateValue)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadataJson)
        }
        startService(serviceIntent)
        
        // Launch the main activity with the appropriate action
        val targetActivityClass = getTargetActivityClass()
        if (targetActivityClass != null) {
            val mainActivityIntent = Intent(this, targetActivityClass).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                action = Intent.ACTION_VIEW
                
                // Set the appropriate action based on the notification action
                when (notificationStateValue) {
                    CallNotificationService.Companion.NotificationState.ANSWER.ordinal -> {
                        putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_ANSWER_CALL)
                    }
                    CallNotificationService.Companion.NotificationState.REJECT.ordinal -> {
                        putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, MyFirebaseMessagingService.ACT_REJECT_CALL)
                    }
                }
                
                // Pass the push metadata
                putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadataJson)
            }
            startActivity(mainActivityIntent)
        } else {
            Timber.e("Could not find target activity class")
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