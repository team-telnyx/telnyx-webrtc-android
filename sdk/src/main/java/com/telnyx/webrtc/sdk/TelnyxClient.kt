package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import org.webrtc.IceCandidate
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

    private var isNetworkCallbackRegistered = false
    private val networkCallback = object : ConnectivityHelper.NetworkCallback() {
        override fun onNetworkAvailable() {
            Timber.d("[%s] :: There is a network available", this@TelnyxClient.javaClass.simpleName)
        }

        override fun onNetworkUnavailable() {
            Timber.d(
                "[%s] :: There is no network available",
                this@TelnyxClient.javaClass.simpleName
            )
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }

    init {
        registerNetworkCallback()
    }

    //MediaPlayer for ringtone / ringbacktone
   // private lateinit var mediaPlayer: MediaPlayer
    private var rawRingtone: Int? = null
    private var rawRingBackTone: Int? = null

    fun connect() {
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            socket.connect(this)
        } else {
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }

    private fun registerNetworkCallback() {
        context.let {
            ConnectivityHelper.registerNetworkStatusCallback(it, networkCallback)
            isNetworkCallbackRegistered = true
        }
    }

    private fun unregisterNetworkCallback() {
        if (isNetworkCallbackRegistered) {
            context.let {
                ConnectivityHelper.unregisterNetworkStatusCallback(it, networkCallback)
                isNetworkCallbackRegistered = false
            }
        }
    }

    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData


    fun credentialLogin(config: CredentialConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingBackTone = it
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = Method.LOGIN.methodName,
            params = LoginParam(
                login_token = null,
                login = user,
                passwd = password,
                userVariables = arrayListOf(),
                loginParams = arrayListOf()
            )
        )

        socket.send(loginMessage)
    }

    fun tokenLogin(config: TokenConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = Method.LOGIN.methodName,
            params = LoginParam(
                login_token = token,
                login = null,
                passwd = null,
                userVariables = arrayListOf(),
                loginParams = arrayListOf()
            )
        )
        socket.send(loginMessage)
    }

    fun disconnect() {
        peerConnection?.disconnect()
        unregisterNetworkCallback()
        socket.destroy()
    }

    fun getSessionId(): String? {
        return sessionId
    }

    override fun onLoginSuccessful(jsonObject: JsonObject) {
        Timber.d(
            "[%s] :: onLoginSuccessful [%s]",
            this@TelnyxClient.javaClass.simpleName,
            jsonObject
        )
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

        resetCallOptions()
        stopMediaPlayer()
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
        playRingtone()

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
        Timber.d(
            "[%s] :: onAnswerReceived [%s]",
            this@TelnyxClient.javaClass.simpleName,
            jsonObject
        )

        /* In case of remote user answer the invite
          local user haas to set remote data in order to have information of both peers of a call
          */
        //set remote description
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        when {
            params.has("sdp") -> {
                val stringSdp = params.get("sdp").asString
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
            earlySDP -> {
                callConnectionResponseLiveData.postValue(Connection.ESTABLISHED)
                val stringSdp = peerConnection?.getLocalDescription()?.description
                socketResponseLiveData.postValue(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            Method.ANSWER.methodName,
                            AnswerResponse(callId, stringSdp!!)
                        )
                    )
                )
            }
            else -> {
                //There was no SDP in the response, there was an error.
                callConnectionResponseLiveData.postValue(Connection.ERROR)
            }
        }
        stopMediaPlayer()
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onMediaReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)

        /* In case of remote user answer the invite
          local user haas to set remote data in order to have information of both peers of a call
          */
        //set remote description
        val params = jsonObject.getAsJsonObject("params")
        if (params.has("sdp")) {
            val stringSdp = params.get("sdp").asString
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

            peerConnection?.onRemoteSessionReceived(sdp)

            //Set internal flag for early retrieval of SDP - generally occurs when a ringback setting is applied in inbound call settings
            earlySDP = true
        } else {
            //There was no SDP in the response, there was an error.
            callConnectionResponseLiveData.postValue(Connection.ERROR)
        }
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        Timber.d(
            "[%s] :: onIceCandidateReceived [%s]",
            this@TelnyxClient.javaClass.simpleName,
            iceCandidate
        )
    }
}