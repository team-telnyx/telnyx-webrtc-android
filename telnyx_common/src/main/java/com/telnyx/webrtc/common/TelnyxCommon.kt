package com.telnyx.webrtc.common

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import com.telnyx.webrtc.common.service.CallForegroundService
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*

/**
 * Singleton class responsible for managing Telnyx client and call states.
 * Important members:
 * currentCall - reference to call which is currently active
 * holdedCalls - flow with list of currently holded calls
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

    private val _holdedCalls = MutableStateFlow<List<Call>>(emptyList())
    val holdedCalls: StateFlow<List<Call>>
        get() = _holdedCalls

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

    internal fun setCurrentCall(context: Context, call: Call?) {
        call?.let { newCall ->
            telnyxClient?.getActiveCalls()?.get(newCall.callId)?.let {
                _currentCall = it
                // Start the CallForegroundService
                startCallService(context, it)
            }
        } ?: run {
            _currentCall = null
            // if we have no active call, stop the CallForegroundService
            stopCallService(context)
        }
    }

    internal fun registerCall(call: Call) {
        val holdStatusObserver = Observer<Boolean> { _ ->
            updateHoldedCalls()
        }
        holdStatusObservers[call] = holdStatusObserver
        call.getIsOnHoldStatus().observeForever(holdStatusObserver)
    }

    internal fun unregisterCall(callId: UUID) {
        holdStatusObservers.entries.find { it.key.callId == callId }?.let { entry ->
            entry.key.getIsOnHoldStatus().removeObserver(entry.value)
        }
    }

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

    private fun stopCallService(context: Context) {
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

    internal fun getTelnyxClient(context: Context): TelnyxClient {
        return _telnyxClient ?: synchronized(this) {
            _telnyxClient ?: TelnyxClient(context.applicationContext).also { _telnyxClient = it }
        }
    }

    internal fun resetTelnyxClient() {
        _telnyxClient = null
    }


    internal fun getSharedPreferences(context: Context): SharedPreferences {
        return sharedPreferences ?: synchronized(this) {
            sharedPreferences ?: context.getSharedPreferences(
                sharedPreferencesKey,
                Context.MODE_PRIVATE
            ).also { sharedPreferences = it }
        }
    }

    internal fun setHandlingPush(value: Boolean) {
        _handlingPush = value
    }

    private fun updateHoldedCalls() {
        _holdedCalls.value = telnyxClient?.getActiveCalls()?.entries?.filter { it.value.getIsOnHoldStatus().value == true }?.map { it.value } ?: emptyList()
    }

}
