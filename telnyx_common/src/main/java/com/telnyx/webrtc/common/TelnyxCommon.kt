package com.telnyx.webrtc.common

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import com.telnyx.webrtc.common.service.CallForegroundService
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.AudioCodec
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.SocketConnectionQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.sdk.stats.ICECandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.*

/**
 * Singleton class responsible for managing Telnyx client and call states.
 * Important members:
 * currentCall - reference to call which is currently active
 * heldCalls - flow with list of currently holded calls
 */
class TelnyxCommon private constructor() {
    private var sharedPreferences: SharedPreferences? = null
    private val sharedPreferencesKey = "TelnyxCommonSharedPreferences"

    private var _telnyxClient: TelnyxClient? = null
    val telnyxClient
        get() = _telnyxClient

    private var _currentCall: Call? = null
    val currentCall
        get() = _currentCall

    private val _heldCalls = MutableStateFlow<List<Call>>(emptyList())
    val heldCalls: StateFlow<List<Call>>
        get() = _heldCalls

    /**
     * State flow for call quality metrics of the current call.
     * Observe this flow to display real-time call quality metrics in the UI.
     */
    private val _callQualityMetrics = MutableStateFlow<CallQualityMetrics?>(null)
    val callQualityMetrics: StateFlow<CallQualityMetrics?> = _callQualityMetrics.asStateFlow()

    /**
     * State flow for socket connection metrics.
     * Observe this flow to display real-time connection quality in the UI.
     */
    private val _connectionMetrics = MutableStateFlow<SocketConnectionMetrics?>(null)
    val connectionMetrics: StateFlow<SocketConnectionMetrics?> = _connectionMetrics.asStateFlow()

    private val holdStatusObservers: MutableMap<Call, Observer<Boolean>> = mutableMapOf()

    private var _handlingPush = false
    val handlingPush
        get() = _handlingPush

    companion object {
        @Volatile
        private var instance: TelnyxCommon? = null

        /**
         * Returns the singleton instance of TelnyxCommon.
         *
         * @return The singleton instance.
         */
        fun getInstance(): TelnyxCommon {
            return instance ?: synchronized(this) {
                instance ?: TelnyxCommon().also { instance = it }
            }
        }
    }

    /**
     * Sets the current active call.
     * Updates the internal `_currentCall` reference, starts/stops the call service,
     * and sets up/clears call quality updates accordingly.
     *
     * @param context The application context.
     * @param call The new current call, or null if there is no active call.
     */
    internal fun setCurrentCall(context: Context, call: Call?) {
        call?.let { newCall ->
            telnyxClient?.getActiveCalls()?.get(newCall.callId)?.let {
                _currentCall = it
                // Start the CallForegroundService - if one is not already running
                startCallService(context, it)
                // Setup call quality updates for the new current call
                setupCallQualityUpdates(it)
            }
        } ?: run {
            _currentCall = null
            // if we have no active call, stop the CallForegroundService
            stopCallService(context)
            // Clear call quality updates as there is no current call
            setupCallQualityUpdates(null)
            // reset handling push flag, even if we were not previously handling push
            _handlingPush = false
        }
    }

    /**
     * Registers a call to observe its hold status.
     * When the hold status changes, `updateHeldCalls` is triggered.
     *
     * @param call The call to register.
     */
    internal fun registerCall(call: Call) {
        val holdStatusObserver = Observer<Boolean> { _ ->
            updateHeldCalls()
        }
        holdStatusObservers[call] = holdStatusObserver
        call.getIsOnHoldStatus().observeForever(holdStatusObserver)
    }

    /**
     * Unregisters a call, removing its hold status observer.
     *
     * @param callId The UUID of the call to unregister.
     */
    internal fun unregisterCall(callId: UUID) {
        holdStatusObservers.entries.find { it.key.callId == callId }?.let { entry ->
            entry.key.getIsOnHoldStatus().removeObserver(entry.value)
        }
    }

    /**
     * Starts the `CallForegroundService` if it's not already running.
     * Uses call details to provide information for the service notification.
     *
     * @param viewContext The context, preferably from a View or Activity.
     * @param call The call for which the service is being started.
     */
    private fun startCallService(viewContext: Context, call: Call?) {
        // Check if the service is already running
        if (CallForegroundService.isServiceRunning(viewContext)) {
            Timber.d("CallForegroundService is already running, not starting again")
            return
        }

        val pushMetaData = PushMetaData(
            callerName = call?.inviteResponse?.callerIdName ?: "Active Call",
            callerNumber =  call?.inviteResponse?.callerIdNumber ?: "",
            callId =  call?.callId.toString(),
        )

        try {
            // Start the foreground service
            CallForegroundService.startService(viewContext, pushMetaData)
            Timber.d("Started CallForegroundService for ongoing call")
        } catch (e: IllegalStateException) {
            Timber.e(e, "Failed to start CallForegroundService: ${e.message}")
        }
    }

    /**
     * Stops the `CallForegroundService` if it is running.
     *
     * @param context The application context.
     */
    internal fun stopCallService(context: Context) {
        try {
            context.let {
                if (CallForegroundService.isServiceRunning(it)) {
                    CallForegroundService.stopService(it)
                    Timber.d("Stopped CallForegroundService after call ended by remote party")
                }
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "Failed to stop CallForegroundService: ${e.message}")
        }
    }

    /**
     * Retrieves the singleton `TelnyxClient` instance, creating it if necessary.
     *
     * @param context The application context, needed for client initialization.
     * @return The singleton `TelnyxClient` instance.
     */
    internal fun getTelnyxClient(context: Context): TelnyxClient {
        return _telnyxClient ?: synchronized(this) {
            _telnyxClient ?: TelnyxClient(context.applicationContext).also { 
                _telnyxClient = it
                observeConnectionMetrics(it)
            }
        }
    }
    
    /**
     * Observes connection metrics from the TelnyxClient and updates the state flow.
     *
     * @param client The TelnyxClient instance to observe
     */
    private fun observeConnectionMetrics(client: TelnyxClient) {
        CoroutineScope(Dispatchers.Main).launch {
            client.socketConnectionMetricsFlow.collect { metrics ->
                _connectionMetrics.value = metrics
                Timber.d("Connection Quality: ${metrics.quality}, Interval: ${metrics.averageIntervalMs}ms, Jitter: ${metrics.jitterMs}ms")
            }
        }
    }

    /**
     * Resets the singleton `TelnyxClient` instance to null.
     * This forces the client to be re-initialized on the next call to `getTelnyxClient`.
     */
    internal fun resetTelnyxClient() {
        _telnyxClient = null
    }


    /**
     * Retrieves the singleton `SharedPreferences` instance for TelnyxCommon, creating it if necessary.
     *
     * @param context The application context, needed for accessing SharedPreferences.
     * @return The singleton `SharedPreferences` instance.
     */
    internal fun getSharedPreferences(context: Context): SharedPreferences {
        return sharedPreferences ?: synchronized(this) {
            sharedPreferences ?: context.getSharedPreferences(
                sharedPreferencesKey,
                Context.MODE_PRIVATE
            ).also { sharedPreferences = it }
        }
    }

    /**
     * Sets the internal flag indicating whether a push notification is currently being handled.
     *
     * @param value The new value for the flag.
     */
    internal fun setHandlingPush(value: Boolean) {
        _handlingPush = value
    }

    /**
     * Updates the `_holdedCalls` state flow with the list of currently held calls
     * by filtering the active calls from the `TelnyxClient`.
     */
    private fun updateHeldCalls() {
        _heldCalls.value = telnyxClient?.getActiveCalls()?.entries?.filter { it.value.getIsOnHoldStatus().value == true }?.map { it.value } ?: emptyList()
    }

    /**
     * Sets up or tears down the call quality metric updates for a given call.
     * This should typically be the current active call.
     *
     * @param call The call to monitor, or null to clear metrics and stop monitoring.
     */
    private fun setupCallQualityUpdates(call: Call?) {
        // Clear callback for any previously monitored call that is not the new current call
        // (Includes the case where the previous current call is ending)
        telnyxClient?.getActiveCalls()?.values?.filter { it != call }?.forEach {
            if (it.onCallQualityChange != null) {
                it.onCallQualityChange = null
                Timber.d("Cleared call quality callback for previous call: ${it.callId}")
            }
        }

        if (call == null) {
            // Clear metrics if the new state is no active call
            if (_callQualityMetrics.value != null) {
                _callQualityMetrics.value = null
                Timber.d("Cleared call quality metrics as no call is active.")
            }
        } else {
            // Setup callback for the new current call if it doesn't have one already
            if (call.onCallQualityChange == null) {
                call.onCallQualityChange = { metrics: CallQualityMetrics ->
                    _callQualityMetrics.value = metrics
                }
                Timber.d("Set up call quality callback for current call: ${call.callId}")
            }
        }
    }

    /**
     * Returns a list of supported audio codecs available on the device.
     * This is a convenience method that delegates to the TelnyxClient.
     *
     * @param context The application context
     * @return List of [AudioCodec] objects representing the supported audio codecs
     */
    fun getSupportedAudioCodecs(context: Context): List<AudioCodec> {
        return getTelnyxClient(context).getSupportedAudioCodecs()
    }

}
