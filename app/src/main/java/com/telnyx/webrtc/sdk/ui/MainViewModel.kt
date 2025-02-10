/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.utility.telecom.call.TelecomCallService
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userManager: UserManager
) : ViewModel() {

    private var telnyxClient: TelnyxClient? = null

    var currentCall: Call? = null
    private val holdedCalls = mutableSetOf<Call>()
    private var calls: Map<UUID, Call> = mapOf()

    fun initConnection(
        context: Context,
        providedServerConfig: TxServerConfiguration?,
        credentialConfig: CredentialConfig?,
        tokenConfig: TokenConfig?,
        txPushMetaData: String?
    ) {
        Timber.e("initConnection")
        telnyxClient = TelnyxClient(context)

        providedServerConfig?.let {
            telnyxClient?.connect(it, credentialConfig!!, txPushMetaData, true)
        } ?: run {
            if (tokenConfig != null) {
                telnyxClient?.connect(
                    txPushMetaData = txPushMetaData,
                    tokenConfig = tokenConfig,
                    autoLogin = true
                )
            } else {
                telnyxClient?.connect(
                    txPushMetaData = txPushMetaData,
                    credentialConfig = credentialConfig!!,
                    autoLogin = true
                )
            }
        }
    }

    fun startDebugStats() {
        currentCall?.startDebug()
    }

    fun saveUserData(
        userName: String,
        password: String,
        fcmToken: String?,
        callerIdName: String,
        callerIdNumber: String,
        isDev: Boolean
    ) {
        if (!userManager.isUserLogin) {
            userManager.isUserLogin = true
            userManager.sipUsername = userName
            userManager.sipPass = password
            userManager.fcmToken = fcmToken
            userManager.callerIdName = callerIdName
            userManager.callerIdNumber = callerIdNumber
            userManager.isDev = isDev
        }
    }

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? =
        telnyxClient?.getSocketResponse()

    fun getWsMessageResponse(): LiveData<JsonObject>? = telnyxClient?.getWsMessageResponse()

    fun setCurrentCall(callId: UUID) {
        calls = telnyxClient?.getActiveCalls()!!
        Log.e("setCall Previous", currentCall?.callId.toString())
        Log.e("setCall Current", callId.toString())
        
        if (calls.size > 1) {
            currentCall?.let {
                holdedCalls.add(it)
            }
        }
        currentCall = calls[callId]!!
    }

    fun getCallState(): LiveData<CallState>? = currentCall?.getCallState()
    fun getIsMuteStatus(): LiveData<Boolean>? = currentCall?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = currentCall?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? = currentCall?.getIsOnLoudSpeakerStatus()


    fun doLoginWithToken(tokenConfig: TokenConfig) {
        telnyxClient?.tokenLogin(tokenConfig)
    }

    fun sendInvite(
        context: Context,
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String
    ) {
        val call = telnyxClient?.newInvite(
            callerName, callerNumber, destinationNumber,
            clientState, mapOf(Pair("X-test", "123456"))
        )
        // get context
        // Start TelecomCallService for outgoing call
        val intent = Intent(context, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_OUTGOING_CALL
            putExtra(TelecomCallService.EXTRA_NAME, callerName)
            putExtra(TelecomCallService.EXTRA_URI, Uri.fromParts("tel", destinationNumber, null))
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, call?.callId.toString())
        }
        context.startService(intent)
        
        setCurrentCall(call?.callId!!)
    }

    fun acceptCall(context: Context, callId: UUID, destinationNumber: String, callerName: String? = null) {
        telnyxClient?.acceptCall(
            callId,
            destinationNumber,
            mapOf(Pair("X-testAndroid", "123456"))
        )
        
        // Start TelecomCallService for incoming call
        val intent = Intent(context, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_INCOMING_CALL
            putExtra(TelecomCallService.EXTRA_NAME, callerName ?: "Unknown Caller")
            putExtra(TelecomCallService.EXTRA_URI, Uri.fromParts("tel", destinationNumber, null))
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, callId.toString())
        }
        context.startService(intent)
        
        startDebugStats()
    }

    fun disablePushNotifications(sipUserName: String, fcmToken: String) {
        telnyxClient?.disablePushNotification(sipUserName, null, fcmToken)
    }

    fun endCall(context: Context, callId: UUID? = null) {
        callId?.let {
            telnyxClient?.endCall(callId)
        } ?: run {
            Timber.e("Run End call $callId")
            if (currentCall != null) {
                telnyxClient?.endCall(currentCall?.callId!!)
            }
            currentCall?.endCall(currentCall?.callId!!)
        }

        // Update TelecomCallService about call end
        val intent = Intent(context, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_UPDATE_CALL
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, (callId ?: currentCall?.callId).toString())
        }
        context.startService(intent)

        holdedCalls.lastOrNull()?.let {
            currentCall = it

            if (currentCall?.getIsOnHoldStatus()?.value == true)
                onHoldUnholdPressed(currentCall?.callId!!)

            holdedCalls.remove(it)
        }
    }

    fun onHoldUnholdPressed(callId: UUID) {
        currentCall?.onHoldUnholdPressed(callId)
        currentCall?.let {
            if (it.getIsOnHoldStatus().value == true)
                holdedCalls.add(it)

            if (it.getIsOnHoldStatus().value == false)
                holdedCalls.remove(it)
        }
    }

    fun onMuteUnmutePressed() {
        currentCall?.onMuteUnmutePressed()
    }

    fun onLoudSpeakerPressed() {
        Timber.e("onLoudSpeakerPressed ${currentCall?.callId}")
        currentCall?.onLoudSpeakerPressed()
    }

    fun dtmfPressed(callId: UUID, tone: String) {
        currentCall?.dtmf(callId, tone)
    }

    fun disconnect() {
        Log.d("MainViewModel", "disconnect")
        telnyxClient?.onDisconnect()
        userManager.isUserLogin = false
    }

    fun changeAudioOutput(audioDevice: AudioDevice) {
        telnyxClient?.setAudioOutputDevice(audioDevice)
    }

    fun onByeReceived(callId: UUID) {
        Timber.d("onByeReceived $callId")
        holdedCalls.firstOrNull { it.callId == callId }?.let {
            holdedCalls.remove(it)
        }
    }
}
