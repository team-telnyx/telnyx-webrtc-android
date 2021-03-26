package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.telnyx.webrtc.sdk.*
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.util.*
import javax.inject.Inject

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
@HiltViewModel
class MainViewModel @Inject constructor(
    private val userManager: UserManager
) : ViewModel() {

    private var socketConnection: TxSocket? = null
    private var telnyxClient: TelnyxClient? = null

    private var currentCall: Call? = null
    private var calls: MutableMap<UUID, Call> = mutableMapOf()

    fun initConnection(context: Context) {
        socketConnection = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        telnyxClient = TelnyxClient(socketConnection!!, context)
        telnyxClient!!.connect()
    }

    fun saveUserData(
        userName: String,
        password: String,
        callerIdName: String,
        callerIdNumber: String
    ) {
        if (!userManager.isUserLogin) {
            userManager.isUserLogin = true
            userManager.sipUsername = userName
            userManager.sipPass = password
            userManager.calledIdName = callerIdName
            userManager.callerIdNumber = callerIdNumber
        }
    }

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? =
        telnyxClient?.getSocketResponse()

    fun getActiveCalls() {
        //non null asserted as it will always be initialized as an empty mutable map
       calls = telnyxClient?.getActiveCalls()!!
    }

    fun setCurrentCall() {
       // currentCall = calls.get()
    }

    fun getCallState(): LiveData<CallState>? = telnyxClient?.call?.getCallState()
    fun getIsMuteStatus(): LiveData<Boolean>? = telnyxClient?.call?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = telnyxClient?.call?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? =
        telnyxClient?.call?.getIsOnLoudSpeakerStatus()

    fun doLoginWithCredentials(credentialConfig: CredentialConfig) {
        telnyxClient?.credentialLogin(credentialConfig)
    }

    fun doLoginWithToken(tokenConfig: TokenConfig) {
        telnyxClient?.tokenLogin(tokenConfig)
    }

    fun sendInvite(destinationNumber: String) {
        telnyxClient?.newInvite(destinationNumber)
    }

    fun acceptCall(callId: String, destinationNumber: String) {
        telnyxClient?.call?.acceptCall(callId, destinationNumber)
    }

    fun endCall(callId: String) {
        telnyxClient?.call?.endCall(callId)
    }

    fun onHoldUnholdPressed(callId: String) {
        telnyxClient?.call?.onHoldUnholdPressed(callId)
    }

    fun onMuteUnmutePressed() {
        telnyxClient?.call?.onMuteUnmutePressed()
    }

    fun onLoudSpeakerPressed() {
        telnyxClient?.call?.onLoudSpeakerPressed()
    }

    fun disconnect() {
        telnyxClient?.disconnect()
        userManager.isUserLogin = false
    }

    fun changeAudioOutput(audioDevice: AudioDevice) {
        telnyxClient?.setAudioOutputDevice(audioDevice)
    }
}
