/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userManager: UserManager
) : ViewModel() {

    private var telnyxClient: TelnyxClient? = null

    private var currentCall: Call? = null
    private var previousCall: Call? = null

    private var calls: Map<UUID, Call> = mapOf()

    fun initConnection(context: Context, providedServerConfig: TxServerConfiguration?) {
        telnyxClient = TelnyxClient(context)
        providedServerConfig?.let {
            telnyxClient?.connect(it)
        } ?: run {
            telnyxClient?.connect()
        }
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
        if (calls.size > 1) {
            previousCall = currentCall
        }
        currentCall = calls[callId]
    }

    fun getCallState(): LiveData<CallState>? = currentCall?.getCallState()
    fun getIsMuteStatus(): LiveData<Boolean>? = currentCall?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = currentCall?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? = currentCall?.getIsOnLoudSpeakerStatus()

    fun doLoginWithCredentials(credentialConfig: CredentialConfig) {
        telnyxClient?.credentialLogin(credentialConfig)
    }

    fun doLoginWithToken(tokenConfig: TokenConfig) {
        telnyxClient?.tokenLogin(tokenConfig)
    }

    fun sendInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String
    ) {
        telnyxClient?.call?.newInvite(callerName, callerNumber, destinationNumber, clientState)
    }

    fun acceptCall(callId: UUID, destinationNumber: String) {
        telnyxClient?.call?.acceptCall(callId, destinationNumber)
    }

    fun endCall(callId: UUID? = null) {
        callId?.let {
            telnyxClient?.call?.endCall(callId)
        } ?: run {
            val clientCallId = telnyxClient?.call?.callId
            clientCallId?.let { telnyxClient?.call?.endCall(it) }
        }
        previousCall?.let {
            currentCall = it
        }
    }

    fun onHoldUnholdPressed(callId: UUID) {
        currentCall?.onHoldUnholdPressed(callId)
    }

    fun onMuteUnmutePressed() {
        currentCall?.onMuteUnmutePressed()
    }

    fun onLoudSpeakerPressed() {
        currentCall?.onLoudSpeakerPressed()
    }

    fun dtmfPressed(callId: UUID, tone: String) {
        currentCall?.dtmf(callId, tone)
    }

    fun disconnect() {
        telnyxClient?.disconnect()
        userManager.isUserLogin = false
    }

    fun changeAudioOutput(audioDevice: AudioDevice) {
        telnyxClient?.setAudioOutputDevice(audioDevice)
    }
}
