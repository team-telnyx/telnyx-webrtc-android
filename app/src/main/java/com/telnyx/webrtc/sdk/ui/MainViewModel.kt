package com.telnyx.webrtc.sdk.ui

import android.content.Context
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.telnyx.webrtc.sdk.Peer
import com.telnyx.webrtc.sdk.AppSdpObserver
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
        //ToDo read the comment
        peerConnection = Peer(context)
        peerConnection?.call(AppSdpObserver()) //Maybe we do call first to make SDP We Rename this to createOfferForSDP or something. Then do invite
        telnyxClient?.newInvite(peerConnection!!, destinationNumber)
    }
}
