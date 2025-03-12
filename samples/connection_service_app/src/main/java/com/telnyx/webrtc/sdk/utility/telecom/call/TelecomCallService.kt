package com.telnyx.webrtc.sdk.utility.telecom.call

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCall
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject


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
@AndroidEntryPoint
class TelecomCallService : Service() {


    companion object {
        internal const val EXTRA_NAME: String = "extra_name"
        internal const val EXTRA_URI: String = "extra_uri"
        internal const val EXTRA_TELNYX_CALL_ID: String = "extra_telnyx_call_id"
        internal const val ACTION_INCOMING_CALL = "incoming_call"
        internal const val ACTION_OUTGOING_CALL = "outgoing_call"
        internal const val ACTION_UPDATE_CALL = "update_call"
        internal const val PUSH_METADATA = "push_metadata"
        internal const val ACCEPT_CALL = "accept_call"
    }

    @Inject
    lateinit var telnyxCallManager: TelecomCallManager

    private lateinit var notificationManager: TelecomCallNotificationManager

    @Inject
    lateinit var telecomRepository: TelecomCallRepository

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob())
    private val callScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var fromPush = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = TelecomCallNotificationManager(applicationContext)

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

    override fun onDestroy() {
        super.onDestroy()
        // Remove notification and clean resources
        scope.cancel()
        callScope.cancel()
        notificationManager.updateCallNotification(TelecomCall.None)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            return START_NOT_STICKY
        }

        val telnyxCallIdString =
            intent.getStringExtra(EXTRA_TELNYX_CALL_ID) ?: UUID.randomUUID().toString()

        when (intent.action) {
            ACTION_INCOMING_CALL -> {
                registerCall(
                    intent = intent,
                    incoming = true,
                    telnyxCallId = telnyxCallIdString
                )
            }

            ACTION_OUTGOING_CALL -> {
                registerCall(
                    intent = intent,
                    incoming = false,
                    telnyxCallId = telnyxCallIdString
                )
            }

            ACTION_UPDATE_CALL -> {
                updateServiceState(telecomRepository.currentCall.value)
            }

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

        val pushMetadata = intent.getStringExtra(PUSH_METADATA)

        scope.launch {
            // Check if push metadata is present - if so this is from a push notification and we need to connect on the socket
            pushMetadata?.let { metadata ->
                telnyxCallManager.initConnection(metadata)
                fromPush = true
            } ?: run {
                fromPush = false
            }

            callScope.launch {
                // Register the call with the Telecom stack
                telecomRepository.registerCall(
                    displayName = name,
                    address = uri,
                    isIncoming = incoming,
                    telnyxCallId = UUID.fromString(telnyxCallId),
                )
            }
        }
    }


    /**
     * Update our calling service based on the call state. Here is where you would update the
     * connection socket, the notification, etc...
     */
    private fun updateServiceState(call: TelecomCall) {
        // Always update the notification.
        notificationManager.updateCallNotification(call, fromPush)

        when (call) {
            is TelecomCall.Unregistered -> {
                // The call has ended; we can now stop the service.
                stopSelf()
            }

            else -> {
                // Do nothing
            }

        }
    }

    override fun onBind(intent: Intent): IBinder? = null
}