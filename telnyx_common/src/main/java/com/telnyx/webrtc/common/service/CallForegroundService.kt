/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
import android.os.Binder
import android.os.Build
import android.os.IBinder
import com.google.gson.Gson
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.notification.CallNotificationReceiver
import com.telnyx.webrtc.common.notification.CallNotificationService
import com.telnyx.webrtc.sdk.model.PushMetaData
import timber.log.Timber

/**
 * Foreground service to keep audio alive when the app is minimized during an active call
 */
class CallForegroundService : Service() {

    companion object {
        private const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        private const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        private const val EXTRA_PUSH_METADATA = "EXTRA_PUSH_METADATA"

        /**
         * Start the foreground service with call information
         */
        fun startService(context: Context, pushMetaData: PushMetaData) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_START_SERVICE
                putExtra(EXTRA_PUSH_METADATA, pushMetaData.toJson())
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Timber.d("Starting CallForegroundService")
        }

        /**
         * Stop the foreground service
         */
        fun stopService(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
            Timber.d("Stopping CallForegroundService")
        }
    }

    private val binder = LocalBinder()
    private var callNotificationService: CallNotificationService? = null
    private var pushMetaData: PushMetaData? = null

    inner class LocalBinder : Binder() {
        fun getService(): CallForegroundService = this@CallForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("CallForegroundService created")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callNotificationService = CallNotificationService(this, CallNotificationReceiver::class.java)
        } else {
            Timber.e("CallForegroundService requires Android Oreo or higher")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SERVICE -> {
                val metadataJson = intent.getStringExtra(EXTRA_PUSH_METADATA)
                if (metadataJson != null) {
                    pushMetaData = Gson().fromJson(metadataJson, PushMetaData::class.java)
                    startForeground()
                } else {
                    // If no metadata is provided, create a basic one with current call info
                    val currentCall = TelnyxCommon.getInstance().currentCall
                    if (currentCall != null) {
                        pushMetaData = PushMetaData(
                            callerName = currentCall.inviteResponse?.callerIdName ?: "Unknown Caller",
                            callerNumber = currentCall.inviteResponse?.callerIdNumber ?: "Unknown Number",
                            callId = currentCall.callId.toString()
                        )
                        startForeground()
                    } else {
                        Timber.e("No call information available, stopping service")
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("CallForegroundService destroyed")
    }

    @SuppressLint("NewApi")
    private fun startForeground() {
        pushMetaData?.let { metadata ->
            Timber.d("Starting foreground service with notification for call: ${metadata.callId}")
            
            // Show the ongoing call notification
            callNotificationService?.let { service ->
                val notification = service.createOngoingCallNotification(metadata)
                startForeground(
                    CallNotificationService.NOTIFICATION_ID,
                    notification,
                    FOREGROUND_SERVICE_TYPE_PHONE_CALL or FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            }
        }
    }
}
