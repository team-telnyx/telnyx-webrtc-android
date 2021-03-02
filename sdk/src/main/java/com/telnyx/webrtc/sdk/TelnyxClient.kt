package com.telnyx.webrtc.sdk

import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.verto.receive.AnswerResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import com.telnyx.webrtc.sdk.verto.send.CallDialogParams
import com.telnyx.webrtc.sdk.verto.send.CallParams
import com.telnyx.webrtc.sdk.verto.send.LoginParam
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*

class TelnyxClient(
    var socket: TxSocket,
    var context: Context
) : TxSocketListener {

    private var currentState: State = State.CLOSED
    private var peerConnection: Peer? = null
    private var sessionId: String? = null
    private val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()

    fun connect() {
        socket.connect(this)
    }

    fun login(config: TelnyxConfig){
        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = Method.LOGIN.methodName,
            params = LoginParam(user, password, arrayListOf(), arrayListOf())
        )

        socket.send(loginMessage)
    }

    fun newInvite(destinationNumber: String, sessionId: String) {
       val uuid: String = UUID.randomUUID().toString()
        val callId: String = UUID.randomUUID().toString()

        //Create new peer
        peerConnection = Peer(context)
        //Create offer to generate our local SDP
        peerConnection?.createOfferForSdp(AppSdpObserver())
        //Set up out audio:
        peerConnection?.startLocalAudioCapture()

        val inviteMessageBody = SendingMessageBody(
            id = uuid,
            method = Method.INVITE.methodName,
            params = CallParams(
                sessionId = sessionId,
                sdp = peerConnection?.getLocalDescription()?.description.toString(),
                dialogParams = CallDialogParams(
                    callId = callId,
                    destinationNumber = destinationNumber,
                )
            )
        )

        socket.send(inviteMessageBody)
    }

    fun disconnect() {
        socket.destroy()
    }

    fun getSessionID(): String? {
        return sessionId
    }

    override fun onLoginSuccessful(jsonObject: JsonObject) {
        Timber.d("[%s] :: onLoginSuccessful [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)
        val result = jsonObject.getAsJsonObject("result")
        val sessId = result.get("sessid").asString
        sessionId = sessId
        currentState = State.CONNECTED
    }

    override fun onByeReceived() {
        Timber.d("[%s] :: onByeReceived",this@TelnyxClient.javaClass.simpleName)
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished",this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())
    }
    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)
    }
    override fun onAnswerReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onAnswerReceived [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)

        /* In case of remote user answer the invite
          local user haas to set remote data in order to have information of both peers of a call
          */

        //set remote description
        val params = jsonObject.getAsJsonObject("params")
        if (params.has("sdp")) {
            val stringSdp = params.get("sdp").asString
            val callId = params.get("callID").asString
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

            peerConnection?.onRemoteSessionReceived(sdp)

            socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        Method.ANSWER.methodName,
                        AnswerResponse(callId, stringSdp)
                    )
                )
            )
        }
    }


    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        Timber.d("[%s] :: onIceCandidateReceived [%s]",this@TelnyxClient.javaClass.simpleName, iceCandidate)
    }

    enum class State {
        CONNECTED,
        CLOSED
    }
}