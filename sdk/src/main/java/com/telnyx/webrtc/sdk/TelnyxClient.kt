package com.telnyx.webrtc.sdk

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*

class TelnyxClient(
        var socket: TxSocket,
        var context: Context
) : TxSocketListener {

    private var peerConnection: Peer? = null
    private var sessionId: String? = null
    private val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()
    private val callConnectionResponseLiveData = MutableLiveData<Connection>()

    fun connect() {
        socket.connect(this)
    }

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData

    fun login(config: TelnyxConfig) {
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

    fun newInvite(destinationNumber: String) {
        val uuid: String = UUID.randomUUID().toString()
        val callId: String = UUID.randomUUID().toString()
        var sentFlag = false

        callConnectionResponseLiveData.postValue(Connection.LOADING)

        //Create new peer
        peerConnection = Peer(context,
                object : PeerConnectionObserver() {
                    override fun onIceCandidate(p0: IceCandidate?) {
                        super.onIceCandidate(p0)
                        peerConnection?.addIceCandidate(p0)

                        //set localInfo and ice candidate and able to create correct offer
                        val inviteMessageBody = SendingMessageBody(
                                id = uuid,
                                method = Method.INVITE.methodName,
                                params = CallParams(
                                        sessionId = sessionId!!,
                                        sdp = peerConnection?.getLocalDescription()?.description.toString(),
                                        dialogParams = CallDialogParams(
                                                callId = callId,
                                                destinationNumber = destinationNumber,
                                        )
                                )
                        )

                        if (!sentFlag) {
                            sentFlag = true
                            socket?.send(inviteMessageBody)
                        }
                    }
                })
        peerConnection?.startLocalAudioCapture()
        peerConnection?.createOfferForSdp(AppSdpObserver())
    }

    /* In case of accept a call (accept an invitation)
     local user have to send provided answer (with both local and remote sdps)
   */
    fun acceptCall(callId: String, destinationNumber: String) {
        val uuid: String = UUID.randomUUID().toString()
        val sessionDescriptionString =
                peerConnection?.getLocalDescription()!!.description
        val answerBodyMessage = SendingMessageBody(
                uuid, Method.ANSWER.methodName,
                CallParams(
                        sessionId!!, sessionDescriptionString,
                        CallDialogParams(
                                callId = callId,
                                destinationNumber = destinationNumber
                        )
                )
        )
        socket?.send(answerBodyMessage)
    }

    fun endCall(callId: String) {
        val uuid: String = UUID.randomUUID().toString()
        val byeMessageBody = SendingMessageBody(
                uuid, Method.BYE.methodName,
                ByeParams(
                        sessionId!!,
                        CauseCode.USER_BUSY.code,
                        CauseCode.USER_BUSY.name,
                        ByeDialogParams(
                                callId
                        )
                )
        )
        socket?.send(byeMessageBody)
    }

    fun disconnect() {
        socket.destroy()
    }

    fun getSessionID(): String? {
        return sessionId
    }

    override fun onLoginSuccessful(jsonObject: JsonObject) {
        Timber.d("[%s] :: onLoginSuccessful [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
        sessionId = jsonObject.getAsJsonObject("result").get("sessid").asString
        socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                        ReceivedMessageBody(
                                Method.LOGIN.methodName,
                                LoginResponse(sessionId!!)
                        )
                )
        )
    }

    override fun onByeReceived() {
        Timber.d("[%s] :: onByeReceived", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                        ReceivedMessageBody(
                                Method.BYE.methodName,
                                null
                        )
                )
        )
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)

        /* In case of receiving an invite
          local user should create an answer with both local and remote information :
          1. create a connection peer
          2. setup ice candidate, local description and remote description
          3. connection is ready to be used for answer the call
          */

        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val remoteSdp = params.get("sdp").asString
        val callerName = params.get("caller_id_name").asString
        val callerNumber = params.get("caller_id_number").asString

        peerConnection = Peer(
                context,
                object : PeerConnectionObserver() {
                    override fun onIceCandidate(p0: IceCandidate?) {
                        super.onIceCandidate(p0)
                        peerConnection?.addIceCandidate(p0)
                    }
                }
        )

        peerConnection?.startLocalAudioCapture()

        peerConnection?.onRemoteSessionReceived(
                SessionDescription(
                        SessionDescription.Type.OFFER,
                        remoteSdp
                )
        )

        peerConnection?.answer(AppSdpObserver())

        socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                        ReceivedMessageBody(
                                Method.INVITE.methodName,
                                InviteResponse(callId, remoteSdp, callerName, callerNumber, "")
                        )
                )
        )
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onAnswerReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)

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

            callConnectionResponseLiveData.postValue(Connection.ESTABLISHED)
            socketResponseLiveData.postValue(
                    SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                    Method.ANSWER.methodName,
                                    AnswerResponse(callId, stringSdp)
                            )
                    )
            )
        }
        else {
            //There was no SDP in the response, there was an error.
            callConnectionResponseLiveData.postValue(Connection.ERROR)
        }
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        Timber.d("[%s] :: onIceCandidateReceived [%s]", this@TelnyxClient.javaClass.simpleName, iceCandidate)
    }
}