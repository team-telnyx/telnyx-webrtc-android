package com.telnyx.webrtc.sdk.utility.telecom.call

import android.content.Context
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
import java.util.*

/**
 * A simple manager to wrap the TelnyxClient and track the active call state.
 * This might live inside TelecomCallService or be a separate file
 * that TelecomCallService creates and holds a reference to.
 */
class TelnyxCallManager(
    private val context: Context
) {
    private var telnyxClient: TelnyxClient? = null
    private var currentCall: Call? = null

    // Observables or LiveData/StateFlows for call states
    // e.g. a StateFlow for Mute, OnHold, etc.
    private val _callState = MutableStateFlow(CallState.NEW)
    val callState: StateFlow<CallState> = _callState

    fun initConnection(
        serverConfig: TxServerConfiguration?,
        credentialConfig: CredentialConfig?,
        tokenConfig: TokenConfig?,
        pushMetaData: String?
    ) {
        telnyxClient = TelnyxClient(context)
        if (serverConfig != null && credentialConfig != null) {
            telnyxClient?.connect(
                serverConfig,
                credentialConfig,
                pushMetaData,
                autoLogin = true
            )
        } else if (tokenConfig != null) {
            telnyxClient?.connect(
                tokenConfig = tokenConfig,
                txPushMetaData = pushMetaData,
                autoLogin = true
            )
        } else if (credentialConfig != null) {
            telnyxClient?.connect(
                credentialConfig = credentialConfig,
                txPushMetaData = pushMetaData,
                autoLogin = true
            )
        }
        observeTelnyxSocket()
    }

    private fun observeTelnyxSocket() {
        // Observe the TelnyxClient LiveData or callbacks
        telnyxClient?.getSocketResponse()?.observeForever { socketResponse ->
            handleSocketResponse(socketResponse)
        }
    }

    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        when (response.data?.method) {
            SocketMethod.LOGIN.methodName -> {
                // Logged in, ready to handle calls
            }
            SocketMethod.INVITE.methodName -> {
                // If you need to handle “incoming invites” directly in Telnyx
                // (But presumably you are using the Telecom approach now)
            }
            SocketMethod.ANSWER.methodName -> {
                // The other side answered, etc.
            }
            SocketMethod.BYE.methodName -> {
                // The call ended from the remote side
                // Mark our state as ended
                _callState.value = CallState.DONE
                currentCall = null
            }
        }
    }

    // Example: dial an outgoing call on Telnyx
    fun sendInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String
    ) {
        val newCall = telnyxClient?.newInvite(
            callerName,
            callerNumber,
            destinationNumber,
            clientState = "Sample Client State",
            customHeaders = mapOf("X-test" to "123456")
        )
        currentCall = newCall
        // Possibly start tracking states, attach observers, etc.
        listenToCallState(newCall)
    }

    fun acceptCall(callId: UUID, destinationNumber: String) {
        // In a single-call scenario, we can just assume currentCall
        telnyxClient?.acceptCall(
            callId,
            destinationNumber,
            customHeaders = mapOf("X-testAndroid" to "123456")
        )
        // update your flows, e.g.
        _callState.value = CallState.ACTIVE
    }

    fun endCall() {
        currentCall?.let { c ->
            telnyxClient?.endCall(c.callId)
            _callState.value = CallState.DONE
            currentCall = null
        }
    }

    fun endCallByID(callId: UUID) {
        telnyxClient?.endCall(callId)
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
        call?.getCallState()?.observeForever { newState ->
            _callState.value = newState
        }
    }

    // If you want to tear down everything
    fun disconnect() {
        telnyxClient?.onDisconnect()
    }
}