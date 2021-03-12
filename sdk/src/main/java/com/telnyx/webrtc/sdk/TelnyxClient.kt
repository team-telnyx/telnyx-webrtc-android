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
    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as AudioManager

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
    private var mediaPlayer = MediaPlayer()
    private var rawRingtone: Int? = null
    private var rawRingBackTone: Int? = null

    private var earlySDP = false

    // Ongoing call options
    // Mute toggle live data
    private val muteLiveData = MutableLiveData(false)

    // Hold toggle live data
    private val holdLiveData = MutableLiveData(false)

    // Loud speaker toggle live data
    private val loudSpeakerLiveData = MutableLiveData(false)

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

    fun getIsMuteStatus(): LiveData<Boolean> = muteLiveData
    fun getIsOnHoldStatus(): LiveData<Boolean> = holdLiveData
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean> = loudSpeakerLiveData

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

    fun newInvite(destinationNumber: String) {
        playRingBackTone()
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
        stopMediaPlayer()
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
        resetCallOptions()
        stopMediaPlayer()
    }

    fun onMuteUnmutePressed() {
        if (!muteLiveData.value!!) {
            muteLiveData.postValue(true)
            audioManager.isMicrophoneMute = true
        } else {
            muteLiveData.postValue(false)
            audioManager.isMicrophoneMute = false
        }
    }

    fun onLoudSpeakerPressed() {
        if (!loudSpeakerLiveData.value!!) {
            loudSpeakerLiveData.postValue(true)
            audioManager.isSpeakerphoneOn = true
        } else {
            loudSpeakerLiveData.postValue(false)
            audioManager.isSpeakerphoneOn = false
        }
    }

    fun onHoldUnholdPressed(callId: String) {
        if (!holdLiveData.value!!) {
            holdLiveData.postValue(true)
            sendHoldModifier(callId, "hold")
        } else {
            holdLiveData.postValue(false)
            sendHoldModifier(callId, "unhold")
        }
    }

    private fun sendHoldModifier(callId: String, holdAction: String) {
        val uuid: String = UUID.randomUUID().toString()
        val modifyMessageBody = SendingMessageBody(
            id = uuid,
            method = Method.MODIFY.methodName,
            params = ModifyParams(
                sessid = sessionId!!,
                action = holdAction,
                dialogParams = CallDialogParams(
                    callId = callId,
                )
            )
        )
        socket?.send(modifyMessageBody)
    }


    private fun playRingtone() {
        rawRingtone?.let {
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer.isLooping = true
            mediaPlayer.start()
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    private fun playRingBackTone() {
        rawRingBackTone?.let {
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer.isLooping = true
            mediaPlayer.start()
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    private fun stopMediaPlayer() {
        if (mediaPlayer.isPlaying) {
            mediaPlayer.stop()
            mediaPlayer.release()
            Timber.d("ringtone/ringback media player stopped and released")
        }
    }

    fun disconnect() {
        peerConnection?.disconnect()
        unregisterNetworkCallback()
        socket.destroy()
    }

    fun getSessionId(): String? {
        return sessionId
    }

    private fun resetCallOptions() {
        holdLiveData.postValue(false)
        muteLiveData.postValue(false)
        loudSpeakerLiveData.postValue(false)
        earlySDP = false
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