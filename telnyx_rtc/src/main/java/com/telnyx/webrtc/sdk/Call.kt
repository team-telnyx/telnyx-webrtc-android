/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonSyntaxException
import com.telnyx.webrtc.lib.SessionDescription
import com.telnyx.webrtc.sdk.TelnyxClient.Companion.TIMEOUT_DIVISOR
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.CallNetworkChangeReason
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.sdk.stats.ICECandidate
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.encodeBase64
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import java.util.*
import java.util.Timer
import kotlin.concurrent.timerTask

/**
 * Data class to represent custom headers
 * @param name the name of the custom header
 * @param value the value of the custom header
 */
data class CustomHeaders(val name: String, val value: String)

/**
 * Class that represents a Call and handles all call related actions, including answering and ending a call.
 *
 * @param context the current application Context
 * @param client the [TelnyxClient] instance in use.
 * @param socket the [TxSocket] instance in use
 * @param sessionId the session ID of the user session
 * @param audioManager the [AudioManager] instance in use, used to change audio related settings.
 */
data class Call(
    val context: Context,
    val client: TelnyxClient,
    var socket: TxSocket,
    val sessionId: String,
    val audioManager: AudioManager,
    val providedTurn: String = Config.DEFAULT_TURN,
    val providedStun: String = Config.DEFAULT_STUN,
    internal val mutableCallStateFlow: MutableStateFlow<CallState> = MutableStateFlow(CallState.DONE()),
) {
    
    /**
     * Callback for real-time call quality metrics
     * This is triggered whenever new WebRTC statistics are available
     */
    var onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null

    companion object {
        const val ICE_CANDIDATE_DELAY: Long = 400L
        const val ICE_CANDIDATE_PERIOD: Long = 400L
    }

    internal var peerConnection: Peer? = null

    internal var earlySDP = false

    var inviteResponse: InviteResponse? = null
    var answerResponse: AnswerResponse? = null
    lateinit var callId: UUID

    internal var telnyxSessionId: UUID? = null
    internal var telnyxLegId: UUID? = null

    val callStateFlow: StateFlow<CallState> = mutableCallStateFlow

    private val callStateLiveData = callStateFlow.asLiveData()

    // Ongoing call options
    // Mute toggle live data
    private val muteLiveData = MutableLiveData(false)

    // Hold toggle live data
    private val holdLiveData = MutableLiveData(false)

    // Loud speaker toggle live data
    private val loudSpeakerLiveData = MutableLiveData(false)

    // Per-call ICE candidate management
    private var iceCandidateTimer: Timer? = null
    private val iceCandidateList: MutableList<String> = mutableListOf()
    private var outgoingInviteUUID: String? = null // To store the UUID for the outgoing invite message

    init {
        updateCallState(CallState.CONNECTING)

        // Ensure that loudSpeakerLiveData is correct based on possible options provided from client.
        loudSpeakerLiveData.postValue(audioManager.isSpeakerphoneOn)
    }

    /**
     * Sets the call state to the provided value
     * @param value the new call state
     */
    internal fun updateCallState(value: CallState) {
        mutableCallStateFlow.value = value
    }

    /**
     * Initiates a new call invitation
     * @param callerName, the name to appear on the invitation
     * @param callerNumber, the number to appear on the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @param clientState, the provided client state.
     * @param customHeaders, optional custom SIP headers to include with the call
     * @param debug, when true, enables real-time call quality metrics
     * @see [Call]
     */
    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false
    ) {
        client.newInvite(
            callerName,
            callerNumber,
            destinationNumber,
            clientState,
            customHeaders,
            debug
        )
    }

    fun Map<String, String>.toCustomHeaders(): ArrayList<CustomHeaders> {
        val customHeaders = arrayListOf<CustomHeaders>()
        this.forEach {
            customHeaders.add(CustomHeaders(it.key, it.value))
        }
        return customHeaders
    }

    /**
     * Accepts an incoming call
     * Local user response with both local and remote SDPs
     * @param callId, the callId provided with the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @param customHeaders, optional custom SIP headers to include with the response
     * @param debug, when true, enables real-time call quality metrics
     * @see [Call]
     */
    fun acceptCall(
        callId: UUID,
        destinationNumber: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false
    ) {
        client.acceptCall(callId, destinationNumber, customHeaders, debug)
    }

    /**
     * Accepts an attach invitation
     * Functions the same as the acceptCall but changes the attach param to true
     * @param callId, the callId provided with the invitation
     * @param destinationNumber, the number or SIP name that will receive the invitation
     * @see [Call]
     */
    internal fun acceptReattachCall(callId: UUID, destinationNumber: String) {
        val uuid: String = UUID.randomUUID().toString()
        val sessionDescriptionString =
            peerConnection?.getLocalDescription()?.description
        if (sessionDescriptionString == null) {
            mutableCallStateFlow.value = CallState.ERROR
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
            updateCallState(CallState.ACTIVE)
        }
    }

    /**
     * Ends an ongoing call with a provided callID, the unique UUID belonging to each call
     * @param callId, the callId provided with the invitation
     * @see [Call]
     */
    fun endCall(callId: UUID) {
        client.endCall(callId)
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
        if (!audioManager.isSpeakerphoneOn) {
            loudSpeakerLiveData.postValue(true)
            audioManager.isSpeakerphoneOn = true
        } else {
            loudSpeakerLiveData.postValue(false)
            audioManager.isSpeakerphoneOn = false
        }
        Timber.e("audioManager.isSpeakerphoneOn ${audioManager.isSpeakerphoneOn}")
    }

    /**
     * Returns the current mute status
     * @return [Boolean]
     */
    fun getLoudSpeakerStatus(): Boolean {
        return loudSpeakerLiveData.value!!
    }


    /**
     * Either places a call on hold, or unholds a call based on the current [holdLiveData] value
     * @param callId, the unique UUID of the call you want to place or remove from hold with the [sendHoldModifier] method
     * @see [sendHoldModifier]
     */
    fun onHoldUnholdPressed(callId: UUID) {
        if (!holdLiveData.value!!) {
            holdLiveData.postValue(true)
            updateCallState(CallState.HELD)
            sendHoldModifier(callId, "hold")
        } else {
            holdLiveData.postValue(false)
            updateCallState(CallState.ACTIVE)
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
    @Deprecated("Use `getCallState` instead", ReplaceWith("callStateFlow"))
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
    internal fun resetCallOptions() {
        holdLiveData.postValue(false)
        muteLiveData.postValue(false)
        loudSpeakerLiveData.postValue(false)
        earlySDP = false
        // Reset the call-specific timer and list
        resetIceCandidateTimer()
    }


    /**
     * Converts a JSON array to a list of custom headers
     * @param jsonArray the JSON array to convert
     * @return a list of custom headers
     */
    fun JsonArray.toCustomHeaders(): ArrayList<CustomHeaders> {
        val customHeaders = arrayListOf<CustomHeaders>()
        return try {
            this.forEach {
                customHeaders.add(Gson().fromJson(it, CustomHeaders::class.java))
            }
            Timber.d("customHeaders: $customHeaders")
            customHeaders
        } catch (e: JsonSyntaxException) {
            Timber.e(e)
            customHeaders
        }

    }


    /**
     * Sets the call state to RECONNECTING when a call is being recovered
     */
    fun setCallRecovering() {
        mutableCallStateFlow.value = CallState.RECONNECTING(CallNetworkChangeReason.NETWORK_SWITCH)
    }
    
    /**
     * Sets the call state to ERROR when reconnection timeout occurs
     */
    fun setReconnectionTimeout() {
        mutableCallStateFlow.value = CallState.ERROR
        Logger.e(null,"Call reconnection timed out after ${TelnyxClient.RECONNECT_TIMEOUT/TIMEOUT_DIVISOR} seconds")
    }

    /**
     * Internal function called by the Peer when a new ICE candidate is generated.
     * Adds the candidate to this Call's specific list.
     * @param candidate The ICE candidate string.
     */
    internal fun addIceCandidateInternal(candidate: String) {
        // Potentially add logic here if needed when a candidate is received,
        // but the primary action is adding it to the list for the timer.
        iceCandidateList.add(candidate)
        Logger.d(message = "Call [$callId] received ICE candidate. List size: ${iceCandidateList.size}")
    }

    /**
     * Internal function to start the process for an outgoing call initiated by this Call object.
     * Creates the SDP offer and starts the ICE candidate gathering timer.
     */
    internal fun startOutgoingCallInternal(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>?
    ) {
        // Ensure peerConnection is initialized for this Call instance before proceeding
        if (peerConnection == null) {
            Logger.e(message = "PeerConnection not initialized for Call [$callId] before starting outgoing call.")
            updateCallState(CallState.ERROR)
            return
        }

        outgoingInviteUUID = UUID.randomUUID().toString() // Generate UUID for the invite message

        // Create offer using this Call's peerConnection
        peerConnection?.createOfferForSdp(object : AppSdpObserver() {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                startIceCandidateTimer(
                    callerName,
                    callerNumber,
                    destinationNumber,
                    clientState,
                    customHeaders
                )
            }

            override fun onCreateFailure(p0: String?) {
                 Logger.e(message = "Failed to create SDP offer for Call [$callId]: $p0")
                 updateCallState(CallState.ERROR)
            }
        })
    }

    /**
     * Starts the timer that periodically checks for gathered ICE candidates and sends the invite.
     */
    private fun startIceCandidateTimer(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>?
    ) {
        resetIceCandidateTimer() // Ensure any previous timer is cancelled
        iceCandidateTimer = Timer("call-${this.callId}-iceTimer") // Name the timer thread
        iceCandidateTimer?.schedule(
            timerTask {
                // Check this Call instance's list
                if (iceCandidateList.isNotEmpty()) {
                    // Send invite with gathered candidates
                    val sdpDescription = peerConnection?.getLocalDescription()?.description
                    if (sdpDescription != null && outgoingInviteUUID != null) {
                        val inviteMessageBody = SendingMessageBody(
                            id = outgoingInviteUUID!!, // Use the stored UUID
                            method = SocketMethod.INVITE.methodName,
                            params = CallParams(
                                sessid = sessionId, // Use the session ID associated with this Call
                                sdp = sdpDescription,
                                dialogParams = CallDialogParams(
                                    callerIdName = callerName,
                                    callerIdNumber = callerNumber,
                                    clientState = clientState.encodeBase64(),
                                    callId = callId, // Use the specific call ID assigned to this Call instance
                                    destinationNumber = destinationNumber,
                                    customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf()
                                )
                            )
                        )
                        socket.send(inviteMessageBody)
                        resetIceCandidateTimer() // Stop timer after sending
                    } else {
                         if (sdpDescription == null) Logger.e(message = "Failed to get local SDP description for Call [$callId]. Cannot send invite.")
                         if (outgoingInviteUUID == null) Logger.e(message = "Missing outgoingInviteUUID for Call [$callId]. Cannot send invite.")
                         resetIceCandidateTimer() // Reset timer even on failure
                         updateCallState(CallState.ERROR) // Mark call as errored if SDP/UUID is missing
                    }
                } else {
                    Logger.d(message = "Call [$callId] - Event-ICE_CANDIDATE_DELAY - Waiting for STUN or TURN")
                }
            },
            ICE_CANDIDATE_DELAY, ICE_CANDIDATE_PERIOD
        )
    }


    /**
     * Resets the ICE candidate timer and clears the candidate list for this specific call.
     */
    private fun resetIceCandidateTimer() {
        iceCandidateTimer?.cancel()
        iceCandidateTimer?.purge()
        iceCandidateTimer = null
        iceCandidateList.clear()
        Logger.d(message =  "Call [${this.callId}] ICE candidate timer reset.")
    }
}
