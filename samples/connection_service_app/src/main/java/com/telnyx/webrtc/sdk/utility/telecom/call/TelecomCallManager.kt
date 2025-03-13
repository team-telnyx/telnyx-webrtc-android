package com.telnyx.webrtc.sdk.utility.telecom.call

import android.os.Handler
import android.os.Looper
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.*
import javax.inject.Inject

/**
 * A simple manager to wrap the TelnyxClient and track the active call state.
 * This might live inside TelecomCallService or be a separate file
 * that TelecomCallService creates and holds a reference to.
 */
class TelecomCallManager @Inject constructor(
    private val telnyxClient: TelnyxClient,
    private val userManager: UserManager
) {
    private var currentCall: Call? = null
    private var currentInvite: InviteResponse? = null

    // This is used to track if a push notification was received while the app was in the background - if this is true then we are waiting for a subsequent socket invite after accepting / declining the push notification
    private var pendingPushInvitation = false
    private var acceptPushCall: Boolean? = null

    private val _callState = MutableStateFlow(CallState.NEW)
    val callState: StateFlow<CallState> = _callState

    init {
        CoroutineScope(Dispatchers.IO).launch {
            observeSocketResponse()
        }
    }

    suspend fun initConnection(
        pushMetaData: String
    ) {
        pendingPushInvitation = true
        val loginConfig = CredentialConfig(
            sipUser = userManager.sipUsername,
            sipPassword = userManager.sipPass,
            sipCallerIDName = userManager.callerIdNumber,
            sipCallerIDNumber = userManager.callerIdNumber,
            fcmToken = userManager.fcmToken,
            ringtone = null,
            ringBackTone = null,
            logLevel = LogLevel.ALL,
            debug = false,
        )
        telnyxClient.connect(
            credentialConfig = loginConfig,
            txPushMetaData = pushMetaData,
            autoLogin = true
        )
        observeSocketResponse()
    }

    suspend fun observeSocketResponse() {
        withContext(Dispatchers.Main) {
            telnyxClient.getSocketResponse().observeForever { socketResponse ->
                handleSocketResponse(socketResponse)
            }
        }
    }

    fun getSocketResponse() = telnyxClient.getSocketResponse()

    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        when (response.data?.method) {
            SocketMethod.LOGIN.methodName -> {
                Timber.i("CallManager: Logged in")
            }

            SocketMethod.INVITE.methodName -> {
                Timber.i("CallManager: Incoming call")
                _callState.value = CallState.NEW
                currentInvite = response.data?.result as InviteResponse
                if (pendingPushInvitation && currentInvite != null) {
                    // We received a push notification while the app was in the background and are waiting for the socket invite
                    pendingPushInvitation = false
                    when (acceptPushCall) {
                        true -> acceptCall(currentInvite?.callId!!, currentInvite?.callerIdNumber!!)
                        false -> endCall()
                        null -> // Do nothing
                            Timber.i("CallManager: Waiting for user to accept / decline call")
                    }
                    acceptPushCall = null
                }
            }

            SocketMethod.ANSWER.methodName -> {
                Timber.i("CallManager: Call answered")
            }

            SocketMethod.BYE.methodName -> {
                Timber.i("CallManager: Call ended")
                // The call ended from the remote side
                _callState.value = CallState.DONE
                currentCall = null
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
        if (pendingPushInvitation) {
            // There is no socket invite to accept yet. We are waiting for the socket invite to come in after accepting / declining the push notification
            acceptPushCall = true
            return
        }

        val acceptedCall = telnyxClient.acceptCall(
            callId,
            destinationNumber,
            customHeaders = mapOf("X-testAndroid" to "123456")
        )
        currentCall = acceptedCall
        listenToCallState(currentCall)
        _callState.value = CallState.ACTIVE
    }

    fun endCall() {
        if (pendingPushInvitation) {
            // There is no socket invite to accept yet. We are waiting for the socket invite to come in after accepting / declining the push notification
            acceptPushCall = false
            return
        }
        currentCall?.let { c ->
            telnyxClient.endCall(c.callId)
            _callState.value = CallState.DONE
            currentCall = null
        } ?: currentInvite?.let { invite ->
            telnyxClient.endCall(invite.callId)
            _callState.value = CallState.DONE
            currentInvite = null
        }
    }

    fun endCallByID(callId: UUID) {
        if (pendingPushInvitation) {
            // There is no socket invite to accept yet. We are waiting for the socket invite to come in after accepting / declining the push notification
            acceptPushCall = false
            return
        }
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