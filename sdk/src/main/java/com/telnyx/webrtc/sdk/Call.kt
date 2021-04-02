package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.CauseCode
import com.telnyx.webrtc.sdk.model.SocketMethod
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
    var peerConnection: Peer?,
    var socket: TxCallSocket,
    var callId: UUID,
    var sessionId: String,
    var audioManager: AudioManager,
    var context: Context
) : TxSocketCallListener {

    private var earlySDP = false


    internal val callStateLiveData = MutableLiveData(CallState.NEW)

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


    /* In case of accept a call (accept an invitation)
     local user have to send provided answer (with both local and remote sdps)
   */
    fun acceptCall(destinationNumber: String) {
        val uuid: String = UUID.randomUUID().toString()
        val sessionDescriptionString =
            peerConnection?.getLocalDescription()!!.description
        val answerBodyMessage = SendingMessageBody(
            uuid, SocketMethod.ANSWER.methodName,
            CallParams(
                sessionId, sessionDescriptionString,
                CallDialogParams(
                    callId = this.callId,
                    destinationNumber = destinationNumber
                )
            )
        )
        socket.callSend(answerBodyMessage)
        client.stopMediaPlayer()
        callStateLiveData.postValue(CallState.ACTIVE)
        client.callOngoing()
    }

    fun endCall() {
        val uuid: String = UUID.randomUUID().toString()
        val byeMessageBody = SendingMessageBody(
            uuid, SocketMethod.BYE.methodName,
            ByeParams(
                sessionId,
                CauseCode.USER_BUSY.code,
                CauseCode.USER_BUSY.name,
                ByeDialogParams(
                    this.callId
                )
            )
        )
        callStateLiveData.postValue(CallState.DONE)
        client.removeFromCalls(this.callId)
        client.callNotOngoing()
        socket.callSend(byeMessageBody)
        resetCallOptions()
        client.stopMediaPlayer()
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

    fun onHoldUnholdPressed() {
        if (!holdLiveData.value!!) {
            holdLiveData.postValue(true)
            callStateLiveData.postValue(CallState.HELD)
            sendHoldModifier("hold")
        } else {
            holdLiveData.postValue(false)
            callStateLiveData.postValue(CallState.ACTIVE)
            sendHoldModifier("unhold")
        }
    }

    private fun sendHoldModifier(holdAction: String) {
        val uuid: String = UUID.randomUUID().toString()
        val modifyMessageBody = SendingMessageBody(
            id = uuid,
            method = SocketMethod.MODIFY.methodName,
            params = ModifyParams(
                sessid = sessionId,
                action = holdAction,
                dialogParams = CallDialogParams(
                    callId = this.callId,
                )
            )
        )
        socket.callSend(modifyMessageBody)
    }

    fun getCallState(): LiveData<CallState> = callStateLiveData
    fun getIsMuteStatus(): LiveData<Boolean> = muteLiveData
    fun getIsOnHoldStatus(): LiveData<Boolean> = holdLiveData
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean> = loudSpeakerLiveData

    private fun resetCallOptions() {
        holdLiveData.postValue(false)
        muteLiveData.postValue(false)
        loudSpeakerLiveData.postValue(false)
        earlySDP = false
    }

    override fun onByeReceived(callId: UUID) {
        Timber.d("[%s] :: onByeReceived", this@Call.javaClass.simpleName)
        client.socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.BYE.methodName,
                    null
                )
            )
        )

        callStateLiveData.postValue(CallState.DONE)
        client.removeFromCalls(callId)
        client.callNotOngoing()
        resetCallOptions()
        client.stopMediaPlayer()
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
        //   val callId = params.get("callID").asString
        when {
            params.has("sdp") -> {
                val stringSdp = params.get("sdp").asString
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

                peerConnection?.onRemoteSessionReceived(sdp)

                callStateLiveData.postValue(CallState.ACTIVE)

                client.socketResponseLiveData.postValue(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.ANSWER.methodName,
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
                            SocketMethod.ANSWER.methodName,
                            AnswerResponse(callId, stringSdp!!)
                        )
                    )
                )
            }
            else -> {
                //There was no SDP in the response, there was an error.
                callStateLiveData.postValue(CallState.DONE)
                client.removeFromCalls(this.callId)
            }
        }
        client.callOngoing()
        client.stopMediaPlayer()
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
            client.removeFromCalls(this.callId)
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
        client.onOfferReceived(jsonObject)
    }
}