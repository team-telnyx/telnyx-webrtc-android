package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.telnyx.webrtc.sdk.Peer
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.TelnyxConfig
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse


@HiltViewModel
class MainViewModel @Inject constructor(
        @ApplicationContext val context: Context,
        private val userManager: UserManager
) : ViewModel() {

    private var socketConnection: TxSocket? = null
    private var telnyxClient: TelnyxClient? = null

    fun initConnection() {
        socketConnection = TxSocket(
                host_address = "rtc.telnyx.com",
                port = 14938
        )
        telnyxClient = TelnyxClient(socketConnection!!, context)
        telnyxClient!!.connect()
    }

    fun doLogin(loginConfig: TelnyxConfig) {
        telnyxClient?.login(loginConfig)
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

    fun sendInvite(destinationNumber: String) {
        telnyxClient?.newInvite(destinationNumber)
    }

    fun acceptCall(callId: String, destinationNumber: String) {
        telnyxClient?.acceptCall(callId, destinationNumber)
    }

    fun endCall(callId: String) {
        telnyxClient?.endCall(callId)
    }
}
