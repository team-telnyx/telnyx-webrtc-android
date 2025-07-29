/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.google.gson.Gson
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.util.toCredentialConfig
import com.telnyx.webrtc.common.util.toTokenConfig
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.LoginResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.lifecycle.Observer
import kotlinx.coroutines.flow.collectLatest

/**
 * Background service for handling call decline without launching the main application.
 * This service connects to the socket, sends a login message with decline_push parameter,
 * and then disconnects.
 */
class BackgroundCallDeclineService : Service() {

    companion object {
        const val EXTRA_TX_PUSH_METADATA = "tx_push_metadata"
        private const val CONNECTION_TIMEOUT_MS = 10000L // 10 seconds timeout

        /**
         * Starts the background call decline service.
         *
         * @param context The application context.
         * @param txPushMetadata The push metadata containing call information.
         */
        fun startService(context: Context, txPushMetadata: PushMetaData?) {
            val intent = Intent(context, BackgroundCallDeclineService::class.java).apply {
                putExtra(EXTRA_TX_PUSH_METADATA, txPushMetadata?.toJson())
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var timeoutJob: Job? = null
    private var socketStatusJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("BackgroundCallDeclineService started")

        val txPushMetadataJson = intent?.getStringExtra(EXTRA_TX_PUSH_METADATA)
        val txPushMetadata = if (txPushMetadataJson != null) {
            try {
                Gson().fromJson(txPushMetadataJson, PushMetaData::class.java)
            } catch (e: Exception) {
                Timber.e(e, "Failed to parse push metadata")
                null
            }
        } else {
            null
        }

        if (txPushMetadata != null) {
            performBackgroundDecline(txPushMetadataJson)
        } else {
            Timber.w("No push metadata provided, stopping service")
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Performs the background call decline operation.
     *
     * @param txPushMetaData The push metadata as JSON string.
     */
    private fun performBackgroundDecline(txPushMetaData: String?) {
        serviceScope.launch {
            try {
                val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(this@BackgroundCallDeclineService)
                
                ProfileManager.getProfilesList(this@BackgroundCallDeclineService).lastOrNull()?.let { lastProfile ->
                    val fcmToken = lastProfile.fcmToken ?: ""

                    // Set up timeout to ensure service doesn't run indefinitely
                    timeoutJob = launch {
                        delay(CONNECTION_TIMEOUT_MS)
                        Timber.w("Background decline operation timed out")
                        disconnectAndStop()
                    }

                    // Observe socket responses to handle login success - must be done on main thread
                    socketStatusJob = launch(Dispatchers.Main) {
                        telnyxClient.socketResponseFlow.collectLatest { response ->
                            handleSocketResponse(response)
                        }
                    }

                    // Connect with decline_push parameter
                    if (!lastProfile.sipToken.isNullOrEmpty()) {
                        telnyxClient.connectWithDeclinePush(
                            TxServerConfiguration(),
                            lastProfile.toTokenConfig(fcmToken),
                            txPushMetaData
                        )
                    } else {
                        telnyxClient.connectWithDeclinePush(
                            TxServerConfiguration(),
                            lastProfile.toCredentialConfig(fcmToken),
                            txPushMetaData
                        )
                    }
                } ?: run {
                    Timber.w("No profile found, stopping service")
                    stopSelf()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error during background decline operation")
                stopSelf()
            }
        }
    }

    /**
     * Handles socket responses during the decline operation.
     *
     * @param response The socket response.
     */
    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        when (response.status) {
            SocketStatus.MESSAGERECEIVED -> {
                if (response.data?.method == SocketMethod.LOGIN.methodName) {
                    val loginResponse = response.data?.result as? LoginResponse
                    if (loginResponse != null) {
                        Timber.d("Login successful for decline operation, disconnecting")
                        // Login successful, now disconnect
                        disconnectAndStop()
                    }
                }
            }
            SocketStatus.ERROR -> {
                Timber.e("Socket error during decline operation: ${response.errorMessage}")
                disconnectAndStop()
            }
            else -> {
                // Handle other statuses if needed
                Timber.d("Socket status: ${response.status}")
            }
        }
    }

    /**
     * Disconnects from the socket and stops the service.
     */
    private fun disconnectAndStop() {
        serviceScope.launch {
            try {
                timeoutJob?.cancel()
                socketStatusJob?.cancel()
                val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(this@BackgroundCallDeclineService)
                telnyxClient.disconnect()
                
                Timber.d("Background decline operation completed, stopping service")
            } catch (e: Exception) {
                Timber.e(e, "Error during disconnect")
            } finally {
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timeoutJob?.cancel()
        socketStatusJob?.cancel()
        
        Timber.d("BackgroundCallDeclineService destroyed")
    }
}
