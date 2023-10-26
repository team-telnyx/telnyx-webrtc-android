/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.CauseCode
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.peer.PeerConnectionObserver
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.utilities.encodeBase64
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*
import kotlin.collections.ArrayList
import kotlin.concurrent.timerTask

/**
 * Class that represents a Call and handles all call related actions, including answering and ending a call.
 *
 * @param context the current application Context
 * @param client the [TelnyxClient] instance in use.
 * @param socket the [TxSocket] instance in use
 * @param sessionId the session ID of the user session
 * @param audioManager the [AudioManager] instance in use, used to change audio related settings.
 */

data class CustomHeaders(val name: String, val value: String)

class Call(
    val context: Context,
    val client: TelnyxClient,
    var socket: TxSocket,
    val sessionId: String,
    val audioManager: AudioManager,
    val providedTurn: String = Config.DEFAULT_TURN,
    val providedStun: String = Config.DEFAULT_STUN
) : TxSocketListener {

    companion object {
        const val ICE_CANDIDATE_DELAY: Long = 400
    }

    private var peerConnection: Peer? = null

    private var earlySDP = false

    lateinit var callId: UUID

    private var telnyxSessionId: UUID? = null
    private var telnyxLegId: UUID? = null

    private val callStateLiveData = MutableLiveData(CallState.NEW)

    // Ongoing call options
    // Mute toggle live data
    private val muteLiveData = MutableLiveData(false)

    // Hold toggle live data
    private val holdLiveData = MutableLiveData(false)

    // Loud speaker toggle live data
    private val loudSpeakerLiveData = MutableLiveData(false)

    init {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        callStateLiveData.postValue(CallState.RINGING)
        // Ensure that loudSpeakerLiveData is correct based on possible options provided from client.
        loudSpeakerLiveData.postValue(audioManager.isSpeakerphoneOn)
    }

    /**
     * Initiates a new call invitation
     * @param callerName, the name to appear on the invitation
     * @param callerNumber, the number to appear on the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @param clientState, the provided client state.
     * @see [Call]
     */
    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String,String>? = null
    ) {
        val uuid: String = UUID.randomUUID().toString()
        val inviteCallId: UUID = UUID.randomUUID()

        // set global call CallID
        callId = inviteCallId

        // Create new peer
        peerConnection = Peer(
            context, client, providedTurn, providedStun,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    peerConnection?.addIceCandidate(p0)
                }
            }
        )

        peerConnection?.startLocalAudioCapture()
        peerConnection?.createOfferForSdp(AppSdpObserver())

        val iceCandidateTimer = Timer()
        iceCandidateTimer.schedule(
            timerTask {
                // set localInfo and ice candidate and able to create correct offer
                val inviteMessageBody = SendingMessageBody(
                    id = uuid,
                    method = SocketMethod.INVITE.methodName,
                    params = CallParams(
                        sessid = sessionId,
                        sdp = peerConnection?.getLocalDescription()?.description.toString(),
                        dialogParams = CallDialogParams(
                            callerIdName = callerName,
                            callerIdNumber = callerNumber,
                            clientState = clientState.encodeBase64(),
                            callId = inviteCallId,
                            destinationNumber = destinationNumber,
                            customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf())
                        )
                    )
                socket.send(inviteMessageBody)
            },
            ICE_CANDIDATE_DELAY
        )

        client.callOngoing()
        client.playRingBackTone()
        client.addToCalls(this)
    }

    private fun Map<String,String>.toCustomHeaders():ArrayList<CustomHeaders>{
        val customHeaders = arrayListOf<CustomHeaders>()
        this.forEach {
            customHeaders.add(CustomHeaders(it.key,it.value))
        }
        return customHeaders
    }

    /**
     * Accepts an incoming call
     * Local user response with both local and remote SDPs
     * @param callId, the callId provided with the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @see [Call]
     */
    fun acceptCall(callId: UUID, destinationNumber: String,customHeaders: Map<String, String>? = null) {
        val uuid: String = UUID.randomUUID().toString()
        val sessionDescriptionString =
            peerConnection?.getLocalDescription()?.description
        if (sessionDescriptionString == null) {
            callStateLiveData.postValue(CallState.ERROR)
        } else {
            val answerBodyMessage = SendingMessageBody(
                uuid, SocketMethod.ANSWER.methodName,
                CallParams(
                    sessid = sessionId,
                    sdp = sessionDescriptionString,
                    dialogParams = CallDialogParams(
                        callId = callId,
                        destinationNumber = destinationNumber,
                        customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf()
                    )
                )
            )
            socket.send(answerBodyMessage)
            client.stopMediaPlayer()
            callStateLiveData.postValue(CallState.ACTIVE)
            client.callOngoing()
            // reset audio mode to communication
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    /**
     * Accepts an attach invitation
     * Functions the same as the acceptCall but changes the attach param to true
     * @param callId, the callId provided with the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @see [Call]
     */
    private fun acceptReattachCall(callId: UUID, destinationNumber: String) {
        val uuid: String = UUID.randomUUID().toString()
        val sessionDescriptionString =
            peerConnection?.getLocalDescription()?.description
        if (sessionDescriptionString == null) {
            callStateLiveData.postValue(CallState.ERROR)
        } else {
            val answerBodyMessage = SendingMessageBody(
                uuid, SocketMethod.ATTACH.methodName,
                CallParams(
                    sessid = sessionId,
                    sdp = sessionDescriptionString,
                    dialogParams = CallDialogParams(
                        attach = true,
                        callId = callId,
                        destinationNumber = destinationNumber
                    )
                )
            )
            socket.send(answerBodyMessage)
            callStateLiveData.postValue(CallState.ACTIVE)
        }
    }

    /**
     * Ends an ongoing call with a provided callID, the unique UUID belonging to each call
     * @param callId, the callId provided with the invitation
     * @see [Call]
     */
    fun endCall(callId: UUID) {
        val uuid: String = UUID.randomUUID().toString()
        val byeMessageBody = SendingMessageBody(
            uuid, SocketMethod.BYE.methodName,
            ByeParams(
                sessionId,
                CauseCode.USER_BUSY.code,
                CauseCode.USER_BUSY.name,
                ByeDialogParams(
                    callId
                )
            )
        )
        // send bye message to the UI
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
        socket.send(byeMessageBody)
        resetCallOptions()
        client.stopMediaPlayer()
        peerConnection?.release()
        peerConnection = null
    }

    /**
     * Either mutes or unmutes the [AudioManager] based on the current [muteLiveData] value
     * @see [AudioManager]
     */
    fun onMuteUnmutePressed() {
        if (!muteLiveData.value!!) {
            muteLiveData.postValue(true)
            audioManager.isMicrophoneMute = true
        } else {
            muteLiveData.postValue(false)
            audioManager.isMicrophoneMute = false
        }
    }

    /**
     * Either enables or disables the [AudioManager] loudspeaker mode based on the current [loudSpeakerLiveData] value
     * @see [AudioManager]
     */
    fun onLoudSpeakerPressed() {
        if (!loudSpeakerLiveData.value!!) {
            loudSpeakerLiveData.postValue(true)
            audioManager.isSpeakerphoneOn = true
        } else {
            loudSpeakerLiveData.postValue(false)
            audioManager.isSpeakerphoneOn = false
        }
    }

    /**
     * Either places a call on hold, or unholds a call based on the current [holdLiveData] value
     * @param callId, the unique UUID of the call you want to place or remove from hold with the [sendHoldModifier] method
     * @see [sendHoldModifier]
     */
    fun onHoldUnholdPressed(callId: UUID) {
        if (!holdLiveData.value!!) {
            holdLiveData.postValue(true)
            callStateLiveData.postValue(CallState.HELD)
            sendHoldModifier(callId, "hold")
        } else {
            holdLiveData.postValue(false)
            callStateLiveData.postValue(CallState.ACTIVE)
            sendHoldModifier(callId, "unhold")
        }
    }

    /**
     * Sends the hold modifier message to Telnyx, placing the specified call on hold or removing it from hold based on a provided holdAction value
     * @param callId, unique UUID of the call to modify
     * @param holdAction, the modification action to perform
     */
    private fun sendHoldModifier(callId: UUID, holdAction: String) {
        val uuid: String = UUID.randomUUID().toString()
        val modifyMessageBody = SendingMessageBody(
            id = uuid,
            method = SocketMethod.MODIFY.methodName,
            params = ModifyParams(
                sessid = sessionId,
                action = holdAction,
                dialogParams = CallDialogParams(
                    callId = callId,
                )
            )
        )
        socket.send(modifyMessageBody)
    }

    /**
     * Sends Dual-Tone Multi-Frequency tones down the current peer connection.
     * @param callId unique UUID of the call to send the DTMF INFO message to
     * @param tone This parameter is treated as a series of characters. The characters 0
     *              through 9, A through D, #, and * generate the associated DTMF tones. Unrecognized characters are ignored.
     */

    fun dtmf(callId: UUID, tone: String) {
        val uuid: String = UUID.randomUUID().toString()
        val infoMessageBody = SendingMessageBody(
            id = uuid,
            method = SocketMethod.INFO.methodName,
            params = InfoParams(
                sessid = sessionId,
                dtmf = tone,
                dialogParams = CallDialogParams(
                    callId = callId,
                )
            )
        )
        socket.send(infoMessageBody)
    }

    /**
     * Returns call state live data
     * @see [CallState]
     * @return [LiveData]
     */
    fun getCallState(): LiveData<CallState> = callStateLiveData

    /**
     * Returns mute state live data
     * @return [LiveData]
     */
    fun getIsMuteStatus(): LiveData<Boolean> = muteLiveData

    /**
     * Returns hold state live data
     * @return [LiveData]
     */
    fun getIsOnHoldStatus(): LiveData<Boolean> = holdLiveData

    /**
     * Returns loudspeaker state live data
     * @return [LiveData]
     */
    fun getIsOnLoudSpeakerStatus(): LiveData<Boolean> = loudSpeakerLiveData

    /**
     * Returns the TelnyxSessionId set as a response
     * from an invite or ringing socket call
     * @return [UUID]
     */
    fun getTelnyxSessionId(): UUID? {
        return telnyxSessionId
    }

    /**
     * Returns the TelnyxSessionId set as a response
     * from an invite or ringing socket call
     * @return [UUID]
     */
    fun getTelnyxLegId(): UUID? {
        return telnyxLegId
    }

    /**
     * Resets all call options, primarily hold, mute and loudspeaker state, as well as the earlySDP boolean value.
     * @return [LiveData]
     */
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
        peerConnection?.release()
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
        // set remote description
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val customHeaders = params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

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
                            AnswerResponse(UUID.fromString(callId), stringSdp,customHeaders?.toCustomHeaders() ?: arrayListOf())
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
                            AnswerResponse(UUID.fromString(callId), stringSdp!!,customHeaders?.toCustomHeaders() ?: arrayListOf())
                        )
                    )
                )
                callStateLiveData.postValue(CallState.ACTIVE)
            }
            else -> {
                // There was no SDP in the response, there was an error.
                callStateLiveData.postValue(CallState.DONE)
                client.removeFromCalls(UUID.fromString(callId))
            }
        }
        client.callOngoing()
        client.stopMediaPlayer()
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onMediaReceived [%s]", this@Call.javaClass.simpleName, jsonObject)

        /* In case of remote user answer the invite
          local user has to set remote data in order to have information of both peers of a call
          */
        // set remote description
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString

        if (params.has("sdp")) {
            val stringSdp = params.get("sdp").asString
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

            peerConnection?.onRemoteSessionReceived(sdp)

            // Set internal flag for early retrieval of SDP - generally occurs when a ringback setting is applied in inbound call settings
            earlySDP = true
        } else {
            // There was no SDP in the response, there was an error.
            callStateLiveData.postValue(CallState.DONE)
            client.removeFromCalls(UUID.fromString(callId))
        }
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@Call.javaClass.simpleName, jsonObject)

        /* In case of receiving an invite
          local user should create an answer with both local and remote information :
          1. create a connection peer
          2. setup ice candidate, local description and remote description
          3. connection is ready to be used for answer the call
          */

        if (jsonObject.has("params")) {
            val params = jsonObject.getAsJsonObject("params")
            val offerCallId = UUID.fromString(params.get("callID").asString)
            val remoteSdp = params.get("sdp").asString
            val callerName = params.get("caller_id_name").asString
            val callerNumber = params.get("caller_id_number").asString
            telnyxSessionId = UUID.fromString(params.get("telnyx_session_id").asString)
            telnyxLegId = UUID.fromString(params.get("telnyx_leg_id").asString)

            // Set global callID
            callId = offerCallId

            //retrieve custom headers
            val customHeaders = params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray
            peerConnection = Peer(
                context, client, providedTurn, providedStun,
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
                        SocketMethod.INVITE.methodName,
                        InviteResponse(callId, remoteSdp, callerName, callerNumber, sessionId, customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf())
                    )
                )
            )
            client.playRingtone()
            client.addToCalls(this)
        } else {
            Timber.d(
                "[%s] :: Invalid offer received, missing required parameters [%s]",
                this@Call.javaClass.simpleName, jsonObject
            )
        }
    }

    override fun onRingingReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onRingingReceived [%s]", this@Call.javaClass.simpleName, jsonObject)
        val params = jsonObject.getAsJsonObject("params")
        telnyxSessionId = if (params.has("telnyx_session_id")) {
            UUID.fromString(params.get("telnyx_session_id").asString)
        } else {
            UUID.randomUUID()
        }
        telnyxLegId = if (params.has("telnyx_leg_id")) {
            UUID.fromString(params.get("telnyx_leg_id").asString)
        } else {
            UUID.randomUUID()
        }
        client.socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.RINGING.methodName,
                    null
                )
            )
        )

    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        callStateLiveData.postValue(CallState.CONNECTING)
        Timber.d(
            "[%s] :: onIceCandidateReceived [%s]",
            this@Call.javaClass.simpleName,
            iceCandidate
        )
    }

    private fun JsonArray.toCustomHeaders():ArrayList<CustomHeaders>{
        val customHeaders = arrayListOf<CustomHeaders>()
        return try {
            this.forEach {
                customHeaders.add(Gson().fromJson(it, CustomHeaders::class.java))
            }
            customHeaders
        }catch (e:Exception){
            Timber.e(e)
            e.printStackTrace()
            customHeaders
        }

     }

    override fun onDisablePushReceived(jsonObject: JsonObject) {
        // Noop
    }

    override fun onAttachReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onAttachReceived [%s]", this@Call.javaClass.simpleName, jsonObject)
        val params = jsonObject.getAsJsonObject("params")
        val callId = UUID.fromString(params.get("callID").asString)
        val remoteSdp = params.get("sdp").asString
        val callerNumber = params.get("caller_id_number").asString

        peerConnection = Peer(
            context, client, providedTurn, providedStun,
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

        val iceCandidateTimer = Timer()
        iceCandidateTimer.schedule(
            timerTask {
                acceptReattachCall(callId, callerNumber)
            },
            ICE_CANDIDATE_DELAY
        )
    }

    override fun setCallRecovering() {
        callStateLiveData.postValue(CallState.RECOVERING)
    }

    override fun pingPong() {
        //NOOP
    }

    override fun onDisconnect() {
        //NOOP
    }

    override fun onClientReady(jsonObject: JsonObject) {
        // NOOP
    }

    override fun onGatewayStateReceived(gatewayState: String, receivedSessionId: String?) {
        // NOOP
    }

    override fun onConnectionEstablished() {
        // NOOP
    }

    override fun onErrorReceived(jsonObject: JsonObject) {
        // NOOP
    }
}
