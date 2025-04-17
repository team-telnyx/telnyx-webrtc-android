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
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
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

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> =
        telnyxClient.getSocketResponse()

    fun getWsMessageResponse(): LiveData<JsonObject> = telnyxClient.getWsMessageResponse()

    fun disablePushNotifications() {
        telnyxClient.disablePushNotification()
    }

    fun disconnect() {
        Log.d("MainViewModel", "disconnect")
        telnyxClient.onDisconnect()
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
    
    /**
     * Creates a new outgoing call with optional debug mode for call quality metrics.
     *
     * @param callerName The name of the caller.
     * @param callerNumber The number of the caller.
     * @param destinationNumber The destination number to call.
     * @param clientState Additional state information.
     * @param customHeaders Optional custom SIP headers.
     * @param debug When true, enables real-time call quality metrics.
     * @return The created outgoing call.
     */
    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false
    ): Call {
        Timber.i("newInvite with debug=$debug")
        val call = telnyxClient.newInvite(
            callerName,
            callerNumber,
            destinationNumber,
            clientState,
            customHeaders,
            debug
        )
        
        // Set up call quality metrics logging if debug is enabled
        if (debug) {
            call.onCallQualityChange = { metrics ->
                // Log the call quality metrics
                Timber.d("Call Quality Metrics - MOS: ${metrics.mos}, Quality: ${metrics.quality}, Jitter: ${metrics.jitter * 1000}ms, RTT: ${metrics.rtt * 1000}ms")
            }
        }
        
        return call
    }
    
    /**
     * Accepts an incoming call with optional debug mode for call quality metrics.
     *
     * @param callId The ID of the call to accept.
     * @param destinationNumber The destination number.
     * @param customHeaders Optional custom SIP headers.
     * @param debug When true, enables real-time call quality metrics.
     * @return The accepted call.
     */
    fun acceptCall(
        callId: UUID,
        destinationNumber: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false
    ): Call {
        Timber.i("acceptCall with debug=$debug")
        val call = telnyxClient.acceptCall(
            callId,
            destinationNumber,
            customHeaders,
            debug
        )
        
        // Set up call quality metrics logging if debug is enabled
        if (debug) {
            call.onCallQualityChange = { metrics ->
                // Log the call quality metrics
                Timber.d("Call Quality Metrics - MOS: ${metrics.mos}, Quality: ${metrics.quality}, Jitter: ${metrics.jitter * 1000}ms, RTT: ${metrics.rtt * 1000}ms")
            }
        }
        
        return call
    }
}
