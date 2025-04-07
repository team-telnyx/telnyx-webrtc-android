/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.service

import android.app.ActivityManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
 * Foreground service to keep audio alive when the app is minimized during an active call.
 * This service is responsible for maintaining the audio connection and displaying a notification
 * to the user when the app is not in the foreground.
 */
class CallForegroundService : Service() {

    companion object {
        /**
         * Action to start the foreground service with call information.
         */
        private const val ACTION_START_SERVICE = "ACTION_START_SERVICE"
        /**
         * Action to stop the foreground service.
         */
        private const val ACTION_STOP_SERVICE = "ACTION_STOP_SERVICE"
        /**
         * Extra key for passing push metadata to the service.
         */
        private const val EXTRA_PUSH_METADATA = "EXTRA_PUSH_METADATA"

        /**
         * Static flag to track if the service is running.
         * This flag is used to prevent multiple instances of the service from running concurrently.
         */
        @Volatile
        private var isServiceRunning = false

        /**
         * Starts the foreground service with call information.
         * This method checks if the service is already running and starts it only if it's not.
         *
         * @param context The application context.
         * @param pushMetaData The push metadata containing call information.
         */
        fun startService(context: Context, pushMetaData: PushMetaData) {
            // Check if service is already running
            if (isServiceRunning) {
                Timber.d("CallForegroundService is already running, not starting again")
                return
            }
            
            // Double-check with system service
            if (isServiceRunningInForeground(context)) {
                Timber.d("CallForegroundService is already running according to system, not starting again")
                return
            }
            
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
         * Stops the foreground service.
         * This method stops the service and sets the [isServiceRunning] flag to false.
         *
         * @param context The application context.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, CallForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.stopService(intent)
            isServiceRunning = false
            Timber.d("Stopping CallForegroundService")
        }

        /**
         * Checks if the service is running in the foreground.
         * This method uses the `ActivityManager` to get a list of running services and checks if
         * the [CallForegroundService] is in the list.
         *
         * @param context The application context.
         * @return True if the service is running in the foreground, false otherwise.
         */
        private fun isServiceRunningInForeground(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (CallForegroundService::class.java.name == service.service.className) {
                    return service.foreground
                }
            }
            return false
        }
        
        /**
         * Public method to check if the service is running
         * Can be called from other components to check service status
         */
        fun isServiceRunning(context: Context): Boolean {
            return isServiceRunning || isServiceRunningInForeground(context)
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
        // Set the service running flag to true
        isServiceRunning = true
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            callNotificationService = CallNotificationService(this, CallNotificationReceiver::class.java)
        } else {
            Timber.e("CallForegroundService requires Android Oreo or higher")
            stopSelf()
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
                        isServiceRunning = false
                        stopSelf()
                    }
                }
            }
            ACTION_STOP_SERVICE -> {
                stopForeground(true)
                isServiceRunning = false
                stopSelf()
            }
            else -> {
                // Unknown action, stop the service
                Timber.e("Unknown action received, stopping service")
                isServiceRunning = false
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
        // Set the service running flag to false
        isServiceRunning = false
        Timber.d("CallForegroundService destroyed")
    }

    private fun startForeground() {
        pushMetaData?.let { metadata ->
            Timber.d("Starting foreground service with notification for call: ${metadata.callId}")
            
            // Show the ongoing call notification
            callNotificationService?.let { service ->
                val notification = service.createOngoingCallNotification(metadata)
                
                try {
                    // Try to start with both phone call and microphone types
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        try {
                            startForeground(
                                CallNotificationService.NOTIFICATION_ID,
                                notification,
                                FOREGROUND_SERVICE_TYPE_PHONE_CALL or FOREGROUND_SERVICE_TYPE_MICROPHONE
                            )
                            Timber.d("Started foreground service with PHONE_CALL and MICROPHONE types")
                        } catch (e: SecurityException) {
                            // If that fails, try with just phone call type
                            Timber.e(e, "Failed to start with MICROPHONE type, falling back to PHONE_CALL only")
                            startForeground(
                                CallNotificationService.NOTIFICATION_ID,
                                notification,
                                FOREGROUND_SERVICE_TYPE_PHONE_CALL
                            )
                            Timber.d("Started foreground service with PHONE_CALL type only")
                        }
                    } else {
                        // For older Android versions
                        startForeground(CallNotificationService.NOTIFICATION_ID, notification)
                        Timber.d("Started foreground service (pre-Q Android version)")
                    }
                } catch (e: SecurityException) {
                    // Last resort fallback
                    Timber.e(e, "Failed to start foreground service with types, using basic startForeground")
                    startForeground(CallNotificationService.NOTIFICATION_ID, notification)
                }
            }
        }
    }
}
