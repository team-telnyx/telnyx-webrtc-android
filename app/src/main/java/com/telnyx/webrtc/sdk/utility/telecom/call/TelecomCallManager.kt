package com.telnyx.webrtc.sdk.utility.telecom.call

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*
import javax.inject.Inject

/**
 * A simple manager to wrap the TelnyxClient and track the active call state.
 * This might live inside TelecomCallService or be a separate file
 * that TelecomCallService creates and holds a reference to.
 */
class TelecomCallManager @Inject constructor(
    private val telnyxClient: TelnyxClient
) {
    private var currentCall: Call? = null

    private val _callState = MutableStateFlow(CallState.NEW)
    val callState: StateFlow<CallState> = _callState

    init {
        observeTelnyxSocket()
    }

    fun initConnection(
        serverConfig: TxServerConfiguration?,
        credentialConfig: CredentialConfig?,
        tokenConfig: TokenConfig?,
        pushMetaData: String?
    ) {
        if (serverConfig != null && credentialConfig != null) {
            telnyxClient.connect(
                serverConfig,
                credentialConfig,
                pushMetaData,
                autoLogin = true
            )
        } else if (tokenConfig != null) {
            telnyxClient.connect(
                tokenConfig = tokenConfig,
                txPushMetaData = pushMetaData,
                autoLogin = true
            )
        } else if (credentialConfig != null) {
            telnyxClient.connect(
                credentialConfig = credentialConfig,
                txPushMetaData = pushMetaData,
                autoLogin = true
            )
        }
    }

    fun observeSocketResponse() {
        telnyxClient.getSocketResponse().observeForever { socketResponse ->
            handleSocketResponse(socketResponse)
        }
    }

    private fun observeTelnyxSocket() {
        telnyxClient.getSocketResponse().observeForever { socketResponse ->
            handleSocketResponse(socketResponse)
        }
    }

    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        when (response.data?.method) {
            SocketMethod.LOGIN.methodName -> {
                Timber.i("CallManager: Logged in")
            }
            SocketMethod.INVITE.methodName -> {
                Timber.i("CallManager: Incoming call")
            }
            SocketMethod.ANSWER.methodName -> {
                Timber.i("CallManager: Call answered")
            }
            SocketMethod.BYE.methodName -> {
                Timber.i("CallManager: Call ended")
                // The call ended from the remote side
                _callState.value = CallState.DONE
                currentCall = null
                // End the call from telecom side
            }
        }
    }

    fun sendInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String
    ) {
        val newCall = telnyxClient.newInvite(
            callerName,
            callerNumber,
            destinationNumber,
            clientState = "Sample Client State",
            customHeaders = mapOf("X-test" to "123456")
        )
        currentCall = newCall
        listenToCallState(newCall)
    }

    fun acceptCall(callId: UUID, destinationNumber: String) {
        val acceptedCall = telnyxClient.acceptCall(
            callId,
            destinationNumber,
            customHeaders = mapOf("X-testAndroid" to "123456")
        )
        currentCall = acceptedCall
        _callState.value = CallState.ACTIVE
    }

    fun endCall() {
        currentCall?.let { c ->
            telnyxClient.endCall(c.callId)
            _callState.value = CallState.DONE
            currentCall = null
        }
    }

    fun endCallByID(callId: UUID) {
        telnyxClient.endCall(callId)
        _callState.value = CallState.DONE
        currentCall = null
    }

    fun onMuteUnmute() {
        currentCall?.onMuteUnmutePressed()
    }

    fun onHoldUnhold() {
        currentCall?.onHoldUnholdPressed(currentCall?.callId!!)
    }

    fun onSpeakerToggle() {
        currentCall?.onLoudSpeakerPressed()
    }

    fun dtmfPressed(dtmf: String) {
        currentCall?.dtmf(currentCall?.callId!!, dtmf)
    }

    private fun listenToCallState(call: Call?) {
        call?.let {
            Handler(Looper.getMainLooper()).post {
                it.getCallState().observeForever { newState ->
                    _callState.value = newState
                }
            }
        }
    }

    fun disconnect() {
        telnyxClient.onDisconnect()
    }
}