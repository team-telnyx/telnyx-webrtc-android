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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import timber.log.Timber

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

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val operationLock = Any()
    private var activeOperation: DeclineOperation? = null

    private class DeclineOperation(val startId: Int) {
        var operationJob: Job? = null
        var timeoutJob: Job? = null
        var socketStatusJob: Job? = null
        var disconnectJob: Job? = null
        private var isCompleting = false

        fun markCompleting(): Boolean = synchronized(this) {
            if (isCompleting) {
                false
            } else {
                isCompleting = true
                true
            }
        }

        fun cancel(reason: String) {
            val cancellation = CancellationException(reason)
            operationJob?.cancel(cancellation)
            timeoutJob?.cancel(cancellation)
            socketStatusJob?.cancel(cancellation)
            disconnectJob?.cancel(cancellation)
        }
    }

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
            performBackgroundDecline(txPushMetadataJson, startId)
        } else {
            Timber.w("No push metadata provided, stopping service")
            stopServiceForStart(startId, "No push metadata provided")
        }

        return START_NOT_STICKY
    }

    /**
     * Performs the background call decline operation.
     *
     * @param txPushMetaData The push metadata as JSON string.
     * @param startId The service start id associated with this decline request.
     */
    private fun performBackgroundDecline(txPushMetaData: String?, startId: Int) {
        val operation = DeclineOperation(startId)
        activateOperation(operation)

        operation.operationJob = serviceScope.launch {
            try {
                val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(this@BackgroundCallDeclineService)

                ProfileManager.getProfilesList(this@BackgroundCallDeclineService).lastOrNull()?.let { lastProfile ->
                    val fcmToken = lastProfile.fcmToken ?: ""

                    // Set up timeout to ensure service doesn't run indefinitely
                    operation.timeoutJob = launch {
                        delay(CONNECTION_TIMEOUT_MS)
                        Timber.w("Background decline operation timed out")
                        finishAndStop(operation, "Background decline operation timed out")
                    }

                    // Observe socket responses to handle login success - must be done on main thread
                    operation.socketStatusJob = launch(Dispatchers.Main) {
                        val replayedResponseCount = telnyxClient.socketResponseFlow.replayCache.size
                        telnyxClient.socketResponseFlow
                            .drop(replayedResponseCount)
                            .collectLatest { response ->
                                handleSocketResponse(response, operation)
                            }
                    }

                    // Connect with decline_push parameter
                    if (!lastProfile.sipToken.isNullOrEmpty()) {
                        telnyxClient.connectWithDeclinePush(
                            serverConfigurationFor(lastProfile.isDev),
                            lastProfile.toTokenConfig(fcmToken),
                            txPushMetaData
                        )
                    } else {
                        telnyxClient.connectWithDeclinePush(
                            serverConfigurationFor(lastProfile.isDev),
                            lastProfile.toCredentialConfig(fcmToken),
                            txPushMetaData
                        )
                    }
                } ?: run {
                    Timber.w("No profile found, stopping service")
                    finishAndStop(operation, "No profile found", disconnect = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error during background decline operation")
                finishAndStop(operation, "Error during background decline operation", disconnect = false)
            }
        }
    }

    private fun activateOperation(operation: DeclineOperation) {
        val previousOperation = synchronized(operationLock) {
            activeOperation.also { activeOperation = operation }
        }

        previousOperation?.let { previous ->
            Timber.d(
                "Superseding background decline startId=${previous.startId} with startId=${operation.startId}"
            )
            previous.cancel("Superseded by newer background decline startId=${operation.startId}")
            try {
                TelnyxCommon.getInstance().getTelnyxClient(this@BackgroundCallDeclineService).disconnect()
            } catch (e: Exception) {
                Timber.e(e, "Failed to disconnect superseded background decline socket")
            }
            stopServiceForStart(previous.startId, "Superseded by newer background decline")
        }
    }

    /**
     * Handles socket responses during the decline operation.
     *
     * @param response The socket response.
     * @param operation The decline operation associated with this response collector.
     */
    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>, operation: DeclineOperation) {
        when (response.status) {
            SocketStatus.MESSAGERECEIVED -> {
                if (response.data?.method == SocketMethod.LOGIN.methodName) {
                    val loginResponse = response.data?.result as? LoginResponse
                    if (loginResponse != null) {
                        Timber.d("Login successful for decline operation, disconnecting")
                        // Login successful, now disconnect
                        finishAndStop(operation, "Background decline operation completed")
                    }
                }
            }
            SocketStatus.ERROR -> {
                Timber.e("Socket error during decline operation: ${response.errorMessage}")
                finishAndStop(operation, "Socket error during decline operation")
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
    private fun finishAndStop(
        operation: DeclineOperation,
        reason: String,
        disconnect: Boolean = true
    ) {
        if (!operation.markCompleting()) {
            Timber.d("Ignoring duplicate finish for background decline startId=${operation.startId}")
            return
        }

        operation.disconnectJob = serviceScope.launch {
            try {
                operation.timeoutJob?.cancel()
                operation.socketStatusJob?.cancel()

                if (disconnect && isActiveOperation(operation)) {
                    val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(this@BackgroundCallDeclineService)
                    telnyxClient.disconnect()
                } else if (disconnect) {
                    Timber.d("Skipping disconnect for superseded background decline startId=${operation.startId}")
                }

                Timber.d("$reason, stopping service for startId=${operation.startId}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Error during disconnect")
            } finally {
                clearActiveOperation(operation)
                stopServiceForStart(operation.startId, reason)
            }
        }
    }

    private fun isActiveOperation(operation: DeclineOperation): Boolean = synchronized(operationLock) {
        activeOperation === operation
    }

    private fun clearActiveOperation(operation: DeclineOperation) {
        synchronized(operationLock) {
            if (activeOperation === operation) {
                activeOperation = null
            }
        }
    }

    private fun stopServiceForStart(startId: Int, reason: String) {
        val stopped = stopSelfResult(startId)
        Timber.d("stopSelfResult($startId)=$stopped: $reason")
    }

    private fun serverConfigurationFor(isDev: Boolean): TxServerConfiguration {
        return if (isDev) {
            TxServerConfiguration.development()
        } else {
            TxServerConfiguration.production()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        synchronized(operationLock) {
            activeOperation.also { activeOperation = null }
        }?.cancel("BackgroundCallDeclineService onDestroy")
        serviceScope.cancel(CancellationException("BackgroundCallDeclineService onDestroy"))

        Timber.d("BackgroundCallDeclineService destroyed")
    }
}
