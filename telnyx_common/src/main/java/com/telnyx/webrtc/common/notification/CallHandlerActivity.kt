/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Transparent activity that handles call actions when the app is in the background
 * This activity is used as an intermediary to handle call actions and then launch the main activity
 */
class CallHandlerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Process the intent
        handleIntent(intent)
        
        // Finish this activity immediately after processing
        finish()
    }
    
    private fun handleIntent(intent: Intent?) {
        if (intent == null) {
            Timber.e("Null intent received in CallHandlerActivity")
            return
        }
        
        val action = intent.getStringExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)
        val txPushMetadataJson = intent.getStringExtra(MyFirebaseMessagingService.TX_PUSH_METADATA)
        
        if (action == null || txPushMetadataJson == null) {
            Timber.e("Missing action or push metadata in CallHandlerActivity")
            return
        }
        
        Timber.d("CallHandlerActivity handling action: $action")
        
        // Set handling push to true to prevent disconnect
        TelnyxCommon.getInstance().setHandlingPush(true)
        
        // Get the target activity class
        val targetActivityClass = getTargetActivityClass()
        if (targetActivityClass == null) {
            Timber.e("Could not determine target activity class")
            return
        }
        
        // Create intent for the main activity
        val mainActivityIntent = Intent(this, targetActivityClass).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = Intent.ACTION_VIEW
            putExtra(MyFirebaseMessagingService.EXT_KEY_DO_ACTION, action)
            putExtra(MyFirebaseMessagingService.TX_PUSH_METADATA, txPushMetadataJson)
        }
        
        // Start the main activity
        startActivity(mainActivityIntent)
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
}