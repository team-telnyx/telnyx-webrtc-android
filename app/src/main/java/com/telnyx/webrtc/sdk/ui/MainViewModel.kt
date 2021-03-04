package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TelnyxConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject


@HiltViewModel
class MainViewModel @Inject constructor(
        private val userManager: UserManager
) : ViewModel() {

    private var socketConnection: TxSocket? = null
    private var telnyxClient: TelnyxClient? = null

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

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>>? = telnyxClient?.getSocketResponse()
    fun getIsMuteStatus(): LiveData<Boolean>? = telnyxClient?.getIsMuteStatus()
    fun getIsOnHoldStatus(): LiveData<Boolean>? = telnyxClient?.getIsOnHoldStatus()
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean>? = telnyxClient?.getIsOnLoudSpeakerStatus()

    fun doLogin(loginConfig: TelnyxConfig) {
        telnyxClient?.login(loginConfig)
    }

    fun sendInvite(destinationNumber: String) {
        telnyxClient?.newInvite(destinationNumber)
    }

    fun acceptCall(callId: String, destinationNumber: String) {
        telnyxClient?.acceptCall(callId, destinationNumber)
    }

    fun endCall(callId: String) {
        telnyxClient?.endCall(callId)
    }

    fun onHoldUnholdPressed(callId: String) {
        telnyxClient?.onHoldUnholdPressed(callId)
    }

    fun onMuteUnmutePressed() {
        telnyxClient?.onMuteUnmutePressed()
    }

    fun onLoudSpeakerPressed() {
        telnyxClient?.onLoudSpeakerPressed()
    }

    fun disconnect() {
        telnyxClient?.disconnect()
        userManager.isUserLogin = false
    }
}
