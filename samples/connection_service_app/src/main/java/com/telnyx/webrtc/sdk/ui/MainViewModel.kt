/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.SharedFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import java.util.*
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userManager: UserManager,
    private val telnyxClient: TelnyxClient
) : ViewModel() {

    private val heldCalls = mutableSetOf<Call>()

    fun initConnection(
        providedServerConfig: TxServerConfiguration?,
        credentialConfig: CredentialConfig?,
        tokenConfig: TokenConfig?,
        txPushMetaData: String?
    ) {
        Timber.i("initConnection")
        providedServerConfig?.let {
            telnyxClient.connect(it, credentialConfig!!, txPushMetaData, true)
        } ?: run {
            if (tokenConfig != null) {
                telnyxClient.connect(
                    txPushMetaData = txPushMetaData,
                    tokenConfig = tokenConfig,
                    autoLogin = true
                )
            } else {
                telnyxClient.connect(
                    txPushMetaData = txPushMetaData,
                    credentialConfig = credentialConfig!!,
                    autoLogin = true
                )
            }
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

    /**
     * Returns the socket response as SharedFlow (recommended)
     */
    fun getSocketResponseFlow(): SharedFlow<SocketResponse<ReceivedMessageBody>> =
        telnyxClient.socketResponseFlow

    /**
     * Returns the socket response as LiveData (deprecated)
     * @deprecated Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> =
        telnyxClient.getSocketResponse()

    fun getWsMessageResponse(): LiveData<JsonObject> = telnyxClient.getWsMessageResponse()

    fun disablePushNotifications() {
        telnyxClient.disablePushNotification()
    }

    fun disconnect() {
        Log.d("MainViewModel", "disconnect")
        telnyxClient.disconnect()
        userManager.isUserLogin = false
    }

    fun changeAudioOutput(audioDevice: AudioDevice) {
        telnyxClient.setAudioOutputDevice(audioDevice)
    }

    fun onByeReceived(callId: UUID) {
        Timber.d("onByeReceived $callId")
        heldCalls.firstOrNull { it.callId == callId }?.let {
            heldCalls.remove(it)
        }
    }
}
