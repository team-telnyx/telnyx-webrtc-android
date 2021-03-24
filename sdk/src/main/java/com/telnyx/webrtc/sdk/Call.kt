package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.CauseCode
import com.telnyx.webrtc.sdk.model.Method
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocketCallListener
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class Call(
    var client: TelnyxClient,
    var socket: TxCallSocket,
    var sessionId: String,
    private var audioManager: AudioManager,
    var context: Context
) : TxSocketCallListener {
    private var peerConnection: Peer? = null

    private var earlySDP = false

    //MediaPlayer for ringtone / ringbacktone
    private lateinit var mediaPlayer: MediaPlayer
    private var rawRingtone: Int? = null
    private var rawRingbackTone: Int? = null

    private val callStateLiveData = MutableLiveData(CallState.NEW)

    // Ongoing call options
    // Mute toggle live data
    private val muteLiveData = MutableLiveData(false)

    // Hold toggle live data
    private val holdLiveData = MutableLiveData(false)

    // Loud speaker toggle live data
    private val loudSpeakerLiveData = MutableLiveData(false)

    init {
        socket.callListen(this)

        //Ensure that loudSpeakerLiveData is correct based on possible options provided from client.
        loudSpeakerLiveData.postValue(audioManager.isSpeakerphoneOn)
    }

    fun newInvite(destinationNumber: String) {
        playRingBackTone()
        val uuid: String = UUID.randomUUID().toString()
        val callId: String = UUID.randomUUID().toString()
        var sentFlag = false

        callStateLiveData.postValue(CallState.RINGING)

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
                        socket.callSend(inviteMessageBody)
                    }
                }
            })
        client.callOngoing()
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
                sessionId, sessionDescriptionString,
                CallDialogParams(
                    callId = callId,
                    destinationNumber = destinationNumber
                )
            )
        )
        socket.callSend(answerBodyMessage)
        stopMediaPlayer()
        callStateLiveData.postValue(CallState.ACTIVE)
        client.callOngoing()
    }

    fun endCall(callId: String) {
        val uuid: String = UUID.randomUUID().toString()
        val byeMessageBody = SendingMessageBody(
            uuid, Method.BYE.methodName,
            ByeParams(
                sessionId,
                CauseCode.USER_BUSY.code,
                CauseCode.USER_BUSY.name,
                ByeDialogParams(
                    callId
                )
            )
        )
        callStateLiveData.postValue(CallState.DONE)
        client.callNotOngoing()
        socket.callSend(byeMessageBody)
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
            callStateLiveData.postValue(CallState.HELD)
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
                sessid = sessionId,
                action = holdAction,
                dialogParams = CallDialogParams(
                    callId = callId,
                )
            )
        )
        socket.callSend(modifyMessageBody)
    }

    fun getCallState(): LiveData<CallState> = callStateLiveData
    fun getIsMuteStatus(): LiveData<Boolean> = muteLiveData
    fun getIsOnHoldStatus(): LiveData<Boolean> = holdLiveData
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean> = loudSpeakerLiveData

    private fun playRingtone() {
        rawRingtone = client.getRawRingtone()
        if (this::mediaPlayer.isInitialized && !mediaPlayer.isPlaying) {
            rawRingtone?.let {
                mediaPlayer = MediaPlayer.create(context, it)
                mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                mediaPlayer.isLooping = true
                if (!mediaPlayer.isPlaying) {
                    mediaPlayer.start()
                }
            } ?: run {
                Timber.d("No ringtone specified :: No ringtone will be played")
            }
        }
    }

    private fun playRingBackTone() {
        rawRingbackTone = client.getRawRingbackTone()
        rawRingbackTone?.let {
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            mediaPlayer.isLooping = true
            if (!mediaPlayer.isPlaying) {
                mediaPlayer.start()
            }
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    private fun stopMediaPlayer() {
        if (this::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.stop()
            Timber.d("ringtone/ringback media player stopped and released")
        }
    }

    private fun resetCallOptions() {
        holdLiveData.postValue(false)
        muteLiveData.postValue(false)
        loudSpeakerLiveData.postValue(false)
        earlySDP = false
    }

    override fun onByeReceived() {
        Timber.d("[%s] :: onByeReceived", this@Call.javaClass.simpleName)
        client.socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    Method.BYE.methodName,
                    null
                )
            )
        )

        client.callNotOngoing()
        resetCallOptions()
        stopMediaPlayer()
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        Timber.d(
            "[%s] :: onAnswerReceived [%s]",
            this@Call.javaClass.simpleName,
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

                callStateLiveData.postValue(CallState.ACTIVE)

                client.socketResponseLiveData.postValue(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            Method.ANSWER.methodName,
                            AnswerResponse(callId, stringSdp)
                        )
                    )
                )
            }
            earlySDP -> {
                callStateLiveData.postValue(CallState.CONNECTING)
                val stringSdp = peerConnection?.getLocalDescription()?.description
                client.socketResponseLiveData.postValue(
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
                callStateLiveData.postValue(CallState.DONE)
            }
        }
        client.callOngoing()
        stopMediaPlayer()
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onMediaReceived [%s]", this@Call.javaClass.simpleName, jsonObject)

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
            callStateLiveData.postValue(CallState.DONE)
        }
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        callStateLiveData.postValue(CallState.CONNECTING)
        Timber.d(
            "[%s] :: onIceCandidateReceived [%s]",
            this@Call.javaClass.simpleName,
            iceCandidate
        )
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        playRingtone()
        /* In case of receiving an invite
          local user should create an answer with both local and remote information :
          1. create a connection peer
          2. setup ice candidate, local description and remote description
          3. connection is ready to be used for answer the call
          */

        callStateLiveData.postValue(CallState.RINGING)

        //ToDo we need to handle what happens when we receive an offer while on an existing call.

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

        client.socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    Method.INVITE.methodName,
                    InviteResponse(callId, remoteSdp, callerName, callerNumber, "")
                )
            )
        )
    }
}