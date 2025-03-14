package com.telnyx.webrtc.common

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var telnyxClient: TelnyxClient? = null

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

    internal fun setCurrentCall(call: Call?) {
        call?.let { newCall ->
            telnyxClient?.getActiveCalls()?.get(newCall.callId)?.let {
                _currentCall = it
            }
        } ?: run {
            _currentCall = null
        }
    }

    internal fun registerCall(call: Call) {
        val holdStatusObserver = Observer<Boolean> { value ->
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

    internal fun getTelnyxClient(context: Context): TelnyxClient {
        return telnyxClient ?: synchronized(this) {
            telnyxClient ?: TelnyxClient(context.applicationContext).also { telnyxClient = it }
        }
    }

    internal fun resetTelnyxClient() {
        telnyxClient = null
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
