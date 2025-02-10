package com.telnyx.webrtc.sdk.utility.telecom.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.PermissionChecker
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCall
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallAction
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.*


/**
 * This service handles the app call logic (show notification, record mic, display audio, etc..).
 * It can get started by the user or by an upcoming push notification to start a call.
 *
 * It holds the call scope used to register a call with the Telecom SDK in our TelecomCallRepository.
 *
 * When registering a call with the Telecom SDK and displaying a CallStyle notification, the SDK will
 * grant you foreground service delegation so there is no need to make this a FGS.
 *
 * Note: you could potentially make this service run in a different process since audio or video
 * calls can consume significant memory, although that would require more complex setup to make it
 * work across multiple process.
 */
class TelecomCallService : Service() {

    companion object {
        internal const val EXTRA_NAME: String = "extra_name"
        internal const val EXTRA_URI: String = "extra_uri"
        internal const val EXTRA_TELNYX_CALL_ID: String = "extra_telnyx_call_id"
        internal const val ACTION_INCOMING_CALL = "incoming_call"
        internal const val ACTION_OUTGOING_CALL = "outgoing_call"
        internal const val ACTION_UPDATE_CALL = "update_call"
        
        private const val NOTIFICATION_CHANNEL_ID = "telnyx_call_service"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var notificationManager: TelecomCallNotificationManager
    private lateinit var telecomRepository: TelecomCallRepository
    private lateinit var telnyxCallManager: TelnyxCallManager

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        telnyxCallManager = TelnyxCallManager(applicationContext)
        notificationManager = TelecomCallNotificationManager(applicationContext)
        telecomRepository =
            TelecomCallRepository.instance ?: TelecomCallRepository.create(
                applicationContext,
                telnyxCallManager
            )

        // Create notification channel for Android O and above
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Telnyx Call Service",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Keeps the call active when the screen is locked"
                setSound(null, null)
                enableVibration(false)
            }

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // Start as a foreground service with a persistent notification
        startForeground(NOTIFICATION_ID, createNotification())

        // Observe call status updates once the call is registered and update the service
        telecomRepository.currentCall
            .onEach { call ->
                updateServiceState(call)
            }
            .onCompletion {
                // If the scope is completed stop the service
                stopSelf()
            }
            .launchIn(scope)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Telnyx Call Service")
            .setContentText("Keeping your call active")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Remove notification and clean resources
        scope.cancel()
        notificationManager.updateCallNotification(TelecomCall.None)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val telnyxCallIdString = intent.getStringExtra(EXTRA_TELNYX_CALL_ID)!!
        when (intent.action) {
            ACTION_INCOMING_CALL -> registerCall(
                intent = intent,
                incoming = true,
                telnyxCallId = telnyxCallIdString
            )

            ACTION_OUTGOING_CALL -> registerCall(
                intent = intent,
                incoming = false,
                telnyxCallId = telnyxCallIdString
            )

            ACTION_UPDATE_CALL -> updateServiceState(telecomRepository.currentCall.value)

            else -> throw IllegalArgumentException("Unknown action")
        }

        return START_STICKY
    }

    private fun registerCall(intent: Intent, incoming: Boolean, telnyxCallId: String) {
        // If we have an ongoing call ignore command
        if (telecomRepository.currentCall.value is TelecomCall.Registered) {
            return
        }

        val name = intent.getStringExtra(EXTRA_NAME)!!
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_URI, Uri::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_URI)!!
        }

        scope.launch {
            if (incoming) {
                // Play ringtone for incoming calls using system ringtone
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (audioManager.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                    val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                    val ringtone = RingtoneManager.getRingtone(applicationContext, ringtoneUri)
                    ringtone.play()
                }
            }

            launch {
                // Register the call with the Telecom stack
                telecomRepository.registerCall(
                    displayName = name,
                    address = uri,
                    isIncoming = incoming,
                    telnyxCallId = UUID.fromString(telnyxCallId),
                )
            }

            if (!incoming) {
                // For outgoing calls, activate immediately
                (telecomRepository.currentCall.value as? TelecomCall.Registered)?.processAction(
                    TelecomCallAction.Activate,
                )
            }
        }
    }

    /**
     * Update our calling service based on the call state. Here is where you would update the
     * connection socket, the notification, etc...
     */
    @SuppressLint("MissingPermission")
    private fun updateServiceState(call: TelecomCall) {
        // Update the call notification
        notificationManager.updateCallNotification(call)

        when (call) {
            is TelecomCall.None -> {
                // Stop any call tasks and clean up
                telnyxCallManager.endCall()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }

            is TelecomCall.Registered -> {
                // Update the foreground notification with call state
                val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                    .setContentTitle("Active Call")
                    .setContentText(call.callAttributes.displayName)
                    .setSmallIcon(R.drawable.ic_mic)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build()

                startForeground(NOTIFICATION_ID, notification)

                // Handle call state
                if (call.isActive) {
                    // Call is active, ensure audio routing is correct
                    val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn = false
                }
            }

            is TelecomCall.Unregistered -> {
                // Stop service and clean resources
                telnyxCallManager.endCall()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun hasMicPermission() =
        PermissionChecker.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PermissionChecker.PERMISSION_GRANTED

}