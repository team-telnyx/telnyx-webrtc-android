package com.telnyx.webrtc.sdk.ui

import android.content.Context
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
    private var peerConnection: Peer? = null
    private val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()

    fun initConnection() {
        socketResponseLiveData.postValue(SocketResponse.loading())
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


    fun sendInvite(
        destinationNumber: String
    ) {

        //ToDo start audio capture from telnyxClient in invite? hmmmmmmMMMM or in the call yokie
        //ToDo create observer/listener in viewModel that will broadcast STATE which then handles UI
        //ToDo Connection State, Call state all need to be braoadcasted so the user can handle it howevever they want.


        // Send invite with the generated SDP.
        telnyxClient?.newInvite(destinationNumber, userManager.sessionId)
    }
}
