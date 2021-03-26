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

    fun getCallState(): LiveData<CallState>? = telnyxClient?.call?.getCallState()
    fun getIsMuteStatus(): LiveData<Boolean>? = telnyxClient?.call?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = telnyxClient?.call?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? = telnyxClient?.call?.getIsOnLoudSpeakerStatus()

    fun doLoginWithCredentials(credentialConfig: CredentialConfig) {
        telnyxClient?.credentialLogin(credentialConfig)
    }

    fun doLoginWithToken(tokenConfig: TokenConfig) {
        telnyxClient?.tokenLogin(tokenConfig)
    }

    fun sendInvite(destinationNumber: String) {
        telnyxClient?.call?.newInvite(destinationNumber)
    }


    //ToDo WHEN THERE IS AN INCOMING CALL - SET Current call to incoming call (look at incoming call in Guillermo's code.) Set Incoming call in TelnyxClient (Changing value of call. That way every time there is an incoming call - call is set. )

    //Option 1:
    //ToDo when a call comes in, add them to the map
    //ToDo then set a currentCall variable in TelnyxClient.
    //ToDo currentCall is what the user interacts with.
    //ToDo we set current call once we interact with UI (Answer / Put Down) -- put down by default let's say
    //ToDo the user decides what to do with it but he needs a list of calls - so when a call comes in we add it and set currentCall which is the one they need to deal with now.

    //ToDo can we minimize the refactor and make the call variable current call automatically? WRITE IT OUT ON PAPER

    // ToDo MOVE send invite out of call? ? ?

    //Option 2
    //ToDo - change call to a var, remove by Lazy and set it to init.
    //ToDo - when a new call is incoming, set call to a new call and add to list.


    fun acceptCall(callId: String, destinationNumber: String) {
        telnyxClient?.call?.acceptCall(callId, destinationNumber)
        //telnyxClient?.calls.put()
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
