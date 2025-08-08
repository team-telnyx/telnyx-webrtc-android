/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient.RingtoneType.RAW
import com.telnyx.webrtc.sdk.TelnyxClient.RingtoneType.URI
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.EARPIECE
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.SPEAKER
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.UNASSIGNED
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.stats.WebRTCReporter
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.TxLogger
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.SessionDescription
import com.telnyx.webrtc.sdk.utilities.SdpUtils
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timerTask

/**
 * The TelnyxClient class that can be used to control the SDK. Create / Answer calls, change audio device, etc.
 *
 * @param context the Context that the application is using
 */
class TelnyxClient(
    var context: Context,
) : TxSocketListener {

    internal var webRTCReportersMap: ConcurrentHashMap<UUID, WebRTCReporter> = ConcurrentHashMap<UUID, WebRTCReporter>()

    /**
     * Enum class that defines the type of ringtone resource.
     *
     * @property RAW The ringtone is a raw resource in the app
     * @property URI The ringtone is referenced by a URI
     */
    enum class RingtoneType {
        RAW,
        URI
    }

    /*
    * Add Later: Support current audio device i.e speaker or earpiece or bluetooth for incoming calls
    * */
    /**
     * Enum class that defines the current audio output mode.
     *
     * @property SPEAKER Audio output through the device's loudspeaker
     * @property EARPIECE Audio output through the device's earpiece
     * @property UNASSIGNED No specific audio output mode assigned
     */
    enum class SpeakerMode {
        SPEAKER,
        EARPIECE,
        UNASSIGNED
    }

    /**
     * Companion object containing constant values used throughout the client.
     */
    companion object {
        /** Number of times to retry registration */
        const val RETRY_REGISTER_TIME = 3

        /** Number of times to retry connection */
        const val RETRY_CONNECT_TIME = 3

        /** Delay in milliseconds before gateway response timeout */
        const val GATEWAY_RESPONSE_DELAY: Long = 3000

        /** Delay in milliseconds before attempting to reconnect */
        const val RECONNECT_DELAY: Long = 1000

        /** Timeout in milliseconds for reconnection attempts (60 seconds) */
        const val RECONNECT_TIMEOUT: Long = 60000

        /** Timeout dividend*/
        const val TIMEOUT_DIVISOR: Long = 1000

        /** SDK version*/
        val SDK_VERSION = BuildConfig.SDK_VERSION
    }

    private var credentialSessionConfig: CredentialConfig? = null
    private var tokenSessionConfig: TokenConfig? = null
    private var reconnecting = false

    // Reconnection timeout timer
    private var reconnectTimeOutJob: Job? = null

    // Gateway registration variables
    private var autoReconnectLogin: Boolean = true
    private var gatewayResponseTimer: Timer? = null
    private var waitingForReg = true
    private var registrationRetryCounter = 0
    private var connectRetryCounter = 0
    private var gatewayState = "idle"
    private var speakerState: SpeakerMode = UNASSIGNED

    internal var socket: TxSocket
    private var providedHostAddress: String? = null
    private var providedPort: Int? = null
    internal var providedTurn: String? = null
    internal var providedStun: String? = null
    private var voiceSDKID: String? = null

    private var isSocketDebug = false

    // MediaPlayer for ringtone / ringbacktone
    private var mediaPlayer: MediaPlayer? = null

    var sessid: String // sessid used to recover calls when reconnecting

    // SharedFlow for socket responses (replaces LiveData)
    private val _socketResponseFlow = MutableSharedFlow<SocketResponse<ReceivedMessageBody>>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the socket response in the form of SharedFlow (recommended)
     * The format of each message is provided in SocketResponse and ReceivedMessageBody
     * @see [SocketResponse]
     * @see [ReceivedMessageBody]
     */
    val socketResponseFlow: SharedFlow<SocketResponse<ReceivedMessageBody>> =
        _socketResponseFlow.asSharedFlow()

    // Deprecated LiveData - kept for backward compatibility
    @Deprecated("Use socketResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    var socketResponseLiveData: MutableLiveData<SocketResponse<ReceivedMessageBody>>

    // SharedFlow for ws messages responses (replaces LiveData)
    private val _wsMessagesResponseFlow = MutableSharedFlow<JsonObject>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the ws messages response in the form of SharedFlow (recommended)
     * The format of each message is provided in JsonObject
     */
    val wsMessagesResponseFlow: SharedFlow<JsonObject> = _wsMessagesResponseFlow.asSharedFlow()

    /**
     * Helper function to emit socket response to both SharedFlow and deprecated LiveData
     */
    fun emitWsMessage(message: JsonObject) {
        _wsMessagesResponseFlow.tryEmit(message)
        wsMessagesResponseLiveData.postValue(message)
    }

    // Deprecated LiveData - kept for backward compatibility
    @Deprecated("Use wsMessagesResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    val wsMessagesResponseLiveData = MutableLiveData<JsonObject>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as? AudioManager

    /**
     * Helper function to emit socket response to both SharedFlow and deprecated LiveData
     * for backward compatibility during the migration period.
     */
    private fun emitSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        // Emit to SharedFlow (new approach)
        _socketResponseFlow.tryEmit(response)

        // Emit to LiveData (deprecated, for backward compatibility)
        socketResponseLiveData.postValue(response)
    }

    // Keeps track of all the created calls by theirs UUIDs
    internal val calls: MutableMap<UUID, Call> = mutableMapOf()

    // Transcript management for AI conversations
    private val _transcript = mutableListOf<TranscriptItem>()
    private val assistantResponseBuffers = mutableMapOf<String, StringBuilder>()

    // Current widget settings from AI conversation
    private var _currentWidgetSettings: WidgetSettings? = null

    // SharedFlow for transcript updates
    private val _transcriptUpdateFlow = MutableSharedFlow<List<TranscriptItem>>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the transcript updates in the form of SharedFlow
     * Contains a list of TranscriptItem objects representing the conversation
     */
    val transcriptUpdateFlow: SharedFlow<List<TranscriptItem>> =
        _transcriptUpdateFlow.asSharedFlow()

    /**
     * Returns the current transcript as an immutable list
     */
    val transcript: List<TranscriptItem>
        get() = _transcript.toList()

    /**
     * Returns the current widget settings from AI conversation
     */
    val currentWidgetSettings: WidgetSettings?
        get() = _currentWidgetSettings

    @Deprecated("telnyxclient.call is deprecated. Use telnyxclient.[option] instead. e.g telnyxclient.newInvite()")
    val call: Call? by lazy {
        if (calls.isNotEmpty()) {
            val allCalls = calls.values
            val activeCall = allCalls.firstOrNull { it.callStateFlow.value == CallState.ACTIVE }
            activeCall ?: allCalls.first() // return the first
        } else {
            buildCall()
        }
    }


    private var isCallPendingFromPush: Boolean = false
    private var pushMetaData: PushMetaData? = null

    /**
     * Processes an incoming call notification from a push message.
     *
     * @param metaData The push notification metadata containing call information
     */
    private fun processCallFromPush(metaData: PushMetaData) {
        Logger.d("processCallFromPush PushMetaData", metaData.toJson())
        isCallPendingFromPush = true
        this.pushMetaData = metaData
    }

    /**
     * Build a call containing all required parameters.
     * Will return null if there has been no session established (No successful connection and login)
     * @return [Call]
     */
    private fun buildCall(): Call? {
        if (!BuildConfig.IS_TESTING.get()) {
            sessid.let {
                return Call(
                    context,
                    this,
                    socket,
                    sessid,
                    audioManager!!,
                    providedTurn!!,
                    providedStun!!,
                )
            }
        } else {
            // We are testing, and will instead return a mocked call.
            return null
        }
    }

    /**
     * Flag to indicate whether to prefetch ICE candidates
     */
    var prefetchIceCandidates: Boolean = false
        private set

    /**
     * Set the flag to indicate whether to prefetch ICE candidates
     * @param value The new value for prefetchIceCandidates
     */
    fun setPrefetchIceCandidates(value: Boolean) {
        prefetchIceCandidates = value
    }

    /**
     * Accepts an incoming call invitation.
     *
     * @param callId The unique identifier of the incoming call
     * @param destinationNumber The phone number or SIP address that received the call
     * @param customHeaders Optional custom SIP headers to include in the response
     * @param debug When true, enables real-time call quality metrics
     * @return The [Call] instance representing the accepted call
     */
    fun acceptCall(
        callId: UUID,
        destinationNumber: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false
    ): Call {
        var callDebug = debug
        var socketPortalDebug = isSocketDebug

        val acceptCall =
            calls[callId] ?: throw IllegalStateException("Call not found for ID: $callId")

        // Use apply block to get the correct context for Call members/extensions
        acceptCall.apply {
            val originalOfferSdp = inviteResponse?.sdp
            if (originalOfferSdp == null) {
                Logger.e(message = "Cannot accept call $callId, original offer SDP is missing.")
                updateCallState(CallState.ERROR)
                return@apply
            }

            // Actions to perform immediately
            client.stopMediaPlayer()
            setSpeakerMode(speakerState)
            client.callOngoing()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    Logger.d(tag = "AcceptCall", message = "Waiting for first ICE candidate...")
                    peerConnection?.firstCandidateDeferred?.await() // Wait for first candidate
                    Logger.d(
                        tag = "AcceptCall",
                        message = "First ICE candidate received. Setting up negotiation complete callback."
                    )

                    // Now set the callback for when ICE negotiation stabilizes (timer expires)
                    peerConnection?.setOnNegotiationComplete { // This also starts the negotiation timer
                        Logger.d(
                            tag = "AcceptCall",
                            message = "ICE negotiation complete. Proceeding to send answer."
                        )
                        val generatedAnswerSdp = peerConnection?.getLocalDescription()?.description
                        if (generatedAnswerSdp == null) {
                            updateCallState(CallState.ERROR)
                            Logger.e(message = "Failed to generate local description (Answer SDP) after negotiation for call $callId")
                        } else {
                            // Use the SdpUtils to modify the SDP
                            val finalAnswerSdp = SdpUtils.modifyAnswerSdpToIncludeOfferCodecs(
                                originalOfferSdp,
                                generatedAnswerSdp
                            )
                            Logger.d(
                                tag = "SDP_Modify",
                                message = "[Original Answer SDP After Wait]:\n$generatedAnswerSdp"
                            )
                            Logger.d(
                                tag = "SDP_Modify",
                                message = "[Final Answer SDP After Wait]:\n$finalAnswerSdp"
                            )

                            val uuid: String = UUID.randomUUID().toString()
                            val answerBodyMessage = SendingMessageBody(
                                uuid, SocketMethod.ANSWER.methodName,
                                CallParams(
                                    sessid = sessionId,
                                    sdp = finalAnswerSdp,
                                    dialogParams = CallDialogParams(
                                        callId = callId,
                                        destinationNumber = destinationNumber,
                                        customHeaders = customHeaders?.toCustomHeaders()
                                            ?: arrayListOf()
                                    )
                                )
                            )
                            socket.send(answerBodyMessage)
                            updateCallState(CallState.ACTIVE)

                            // Start stats collection if debug is enabled
                            if (callDebug || socketPortalDebug) {
                                if (getWebRTCReporter(callId) == null) {
                                    val webRTCReporter = WebRTCReporter(
                                        socket,
                                        callId,
                                        getTelnyxLegId()?.toString(),
                                        peerConnection!!,
                                        callDebug,
                                        socketPortalDebug
                                    )
                                    webRTCReporter.onCallQualityChange = { metrics ->
                                        onCallQualityChange?.invoke(metrics)
                                    }
                                    webRTCReporter.startStats()
                                    addWebRTCReporter(callId, webRTCReporter)
                                }
                            }

                            Logger.d(
                                tag = "AcceptCall",
                                message = "Answer sent successfully for call $callId"
                            )
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(
                        tag = "AcceptCall",
                        message = "Error during async accept process for call $callId: ${e.message}"
                    )
                    updateCallState(CallState.ERROR)
                }
            }
        }

        this.addToCalls(acceptCall) // Keep this outside apply if needed, or it's implicitly done
        // Return the call object immediately (non-blocking)
        return acceptCall
    }


    /**
     * Creates a new outgoing call invitation.
     *
     * @param callerName The name of the caller to display
     * @param callerNumber The phone number of the caller
     * @param destinationNumber The phone number or SIP address to call
     * @param clientState Additional state information to pass with the call
     * @param customHeaders Optional custom SIP headers to include with the call
     * @param debug When true, enables real-time call quality metrics
     * @param preferredCodecs Optional list of preferred audio codecs for the call
     * @return A new [Call] instance representing the outgoing call
     */
    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        preferredCodecs: List<AudioCodec>? = null
    ): Call {
        var callDebug = debug
        var socketPortalDebug = isSocketDebug
        val inviteCallId: UUID = UUID.randomUUID()

        val inviteCall = Call(
            context = context,
            client = this,
            socket = socket,
            sessionId = sessid,
            audioManager = audioManager!!,
            providedTurn = providedTurn!!,
            providedStun = providedStun!!
        ).apply {
            callId = inviteCallId
            updateCallState(CallState.RINGING)

            peerConnection = Peer(
                context,
                client,
                providedTurn,
                providedStun,
                inviteCallId,
                prefetchIceCandidates,
                getForceRelayCandidate()
            ) { candidate ->
                addIceCandidateInternal(candidate)
            }.also {

                // Create reporter if per-call debug is enabled or config debug is enabled
                if (callDebug || socketPortalDebug) {
                    val webRTCReporter =
                        WebRTCReporter(
                            socket,
                            callId,
                            this.getTelnyxLegId()?.toString(),
                            it,
                            callDebug,
                            socketPortalDebug
                        )
                    if (callDebug) {
                        webRTCReporter.onCallQualityChange = { metrics ->
                            onCallQualityChange?.invoke(metrics)
                        }
                    }
                    webRTCReporter.startStats()
                    addWebRTCReporter(callId, webRTCReporter)
                }
            }

            peerConnection?.startLocalAudioCapture()

            startOutgoingCallInternal(
                callerName = callerName,
                callerNumber = callerNumber,
                destinationNumber = destinationNumber,
                clientState = clientState,
                customHeaders = customHeaders,
                preferredCodecs = preferredCodecs
            )

            client.callOngoing()
            client.playRingBackTone()
        }
        this.addToCalls(inviteCall)
        return inviteCall
    }


    /**
     * Ends an ongoing call with a provided callID, the unique UUID belonging to each call
     * @param callId, the callId provided with the invitation
     * @see [Call]
     */
    fun endCall(callId: UUID) {
        val endCall = calls[callId]
        endCall?.apply {
            val uuid: String = UUID.randomUUID().toString()
            // Determine cause code and name based on call state
            val (causeCode, causeName) = when (callStateFlow.value) {
                // When Active or Connecting, use NORMAL_CLEARING
                CallState.ACTIVE -> CauseCode.NORMAL_CLEARING.code to CauseCode.NORMAL_CLEARING.name
                // When Ringing (ie. Rejecting an incoming call), use USER_BUSY
                CallState.RINGING, CallState.CONNECTING -> CauseCode.USER_BUSY.code to CauseCode.USER_BUSY.name
                // Default to NORMAL_CLEARING for other states
                else -> CauseCode.NORMAL_CLEARING.code to CauseCode.NORMAL_CLEARING.name
            }
            val terminationReason = CallTerminationReason(cause = causeName, causeCode = causeCode)

            Logger.d(
                tag = "EndCall",
                message = "Ending call with ID: $callId, current state: ${callStateFlow.value}, cause: $causeName ($causeCode)"
            )

            val byeMessageBody = SendingMessageBody(
                uuid, SocketMethod.BYE.methodName,
                ByeParams(
                    sessionId,
                    causeCode,
                    causeName,
                    ByeDialogParams(
                        callId
                    )
                )
            )

            val byeResponseForUi = ByeResponse(
                callId = callId,
                cause = causeName,
                causeCode = causeCode
                // sipCode and sipReason are null here
            )
            // send bye message to the UI
            client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.BYE.methodName,
                        byeResponseForUi
                    )
                )
            )
            updateCallState(CallState.DONE(terminationReason))

            // Stop reporter before releasing the peer connection
            removeWebRTCReporter(callId)?.stopStats()

            client.removeFromCalls(callId)
            client.callNotOngoing()
            socket.send(byeMessageBody)
            resetCallOptions()
            client.stopMediaPlayer()
            peerConnection?.release()
            peerConnection = null
            answerResponse = null
            inviteResponse = null
            _transcriptUpdateFlow.tryEmit(emptyList())
        }
    }

    /**
     * Add specified call to the calls MutableMap
     * @param call, and instance of [Call]
     */
    internal fun addToCalls(call: Call) {
        calls[call.callId] = call
    }

    /**
     * Remove specified call from the calls MutableMap
     * @param callId, the UUID used to identify a specific
     */
    internal fun removeFromCalls(callId: UUID) {
        calls.remove(callId)
    }

    private var socketReconnection: TxSocket? = null

    internal var isNetworkCallbackRegistered = false
    private val networkCallback = object : ConnectivityHelper.NetworkCallback() {
        override fun onNetworkAvailable() {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: There is a network available",
                    this@TelnyxClient.javaClass.simpleName
                )
            )
            // User has been logged in
            resetGatewayCounters()
            if (reconnecting && credentialSessionConfig != null || tokenSessionConfig != null) {
                runBlocking { reconnectToSocket() }
            }
        }

        override fun onNetworkUnavailable() {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: There is no network available",
                    this@TelnyxClient.javaClass.simpleName
                )
            )
            reconnecting = true

            Handler(Looper.getMainLooper()).postDelayed(Runnable {
                if (!ConnectivityHelper.isNetworkEnabled(context)) {
                    getActiveCalls().forEach { (_, call) ->
                        call.updateCallState(CallState.DROPPED(CallNetworkChangeReason.NETWORK_LOST))
                    }

                    emitSocketResponse(
                        SocketResponse.error(
                            "No Network Connection",
                            null
                        )
                    )

                    // Start the reconnection timer to track timeout
                    startReconnectionTimer()
                } else {
                    //Network is switched here. Either from Wifi to LTE or vice-versa
                    runBlocking { reconnectToSocket() }
                }
            }, RECONNECT_DELAY)
        }
    }

    /**
     * Reconnect to the Telnyx socket using saved Telnyx Config - either Token or Credential based
     * @see [TxSocket]
     * @see [TelnyxConfig]
     */
    private suspend fun reconnectToSocket() = withContext(Dispatchers.Default) {
        // Start the reconnection timer to track timeout
        startReconnectionTimer()

        //Disconnect active calls for reconnection
        getActiveCalls().forEach { (_, call) ->
            webRTCReportersMap.forEach { (_, webRTCReporter) ->
                webRTCReporter.pauseStats()
            }
            call.peerConnection?.disconnect()
            call.updateCallState(CallState.RECONNECTING(CallNetworkChangeReason.NETWORK_SWITCH))
        }

        //Delay for network to be properly established
        delay(RECONNECT_DELAY)

        // Create new socket connection
        socketReconnection = TxSocket(
            socket.host_address,
            socket.port
        )
        // Cancel old socket coroutines
        socket.cancel("TxSocket destroyed, initializing new socket and connecting.")
        // Destroy old socket
        socket.destroy()
        launch {
            // Socket is now the reconnectionSocket
            socket = socketReconnection!!

            if (providedHostAddress == null) {
                providedHostAddress =
                    if (pushMetaData == null) Config.TELNYX_PROD_HOST_ADDRESS
                    else
                        Config.TELNYX_PROD_HOST_ADDRESS
            }

            if (voiceSDKID != null) {
                pushMetaData = PushMetaData(
                    callerName = "",
                    callerNumber = "",
                    callId = "",
                    voiceSdkId = voiceSDKID
                )
            }

            // Connect to new socket
            socket.connect(this@TelnyxClient, providedHostAddress, providedPort, pushMetaData) {

                //We can safely assume that the socket is connected at this point
                // Login with stored configuration
                credentialSessionConfig?.let {
                    credentialLogin(it)
                } ?: tokenLogin(tokenSessionConfig!!)

                // Change an ongoing call's socket to the new socket.
                call?.let { call?.socket = socket }
            }

        }
    }

    init {
        // Generate random UUID for sessid param, convert it to string and set globally
        sessid = UUID.randomUUID().toString()

        // Initialize deprecated LiveData for backward compatibility
        socketResponseLiveData =
            MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())

        // Initialize both SharedFlow and LiveData with initial state
        emitSocketResponse(SocketResponse.initialised())

        socket = TxSocket(
            host_address = Config.TELNYX_PROD_HOST_ADDRESS,
            port = Config.TELNYX_PORT
        )
        registerNetworkCallback()
    }

    private var rawRingtone: Any? = null
    private var rawRingbackTone: Int? = null

    /**
     * Return the saved ringtone reference
     * @returns [Int]
     */
    /**
     * Gets the currently configured ringtone resource.
     *
     * @return The ringtone resource reference, or null if none is set
     */
    fun getRawRingtone(): Any? {
        return rawRingtone
    }

    /**
     * Return the saved ringback tone reference
     * @returns [Int]
     */
    /**
     * Gets the currently configured ringback tone resource.
     *
     * @return The ringback tone resource reference, or null if none is set
     */
    fun getRawRingbackTone(): Int? {
        return rawRingbackTone
    }

    /**
     * Connects to the socket using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required for push calls to work
     *
     */
    @Deprecated(
        "this telnyxclient.connect is deprecated." +
                " Use telnyxclient.connect(providedServerConfig,txPushMetaData," +
                "credential or tokenLogin) instead."
    )
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        txPushMetaData: String? = null,
    ) {

        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        socket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            Logger.d(message = "Provided Host Address: $providedHostAddress")
            socket.connect(this, providedHostAddress, providedPort, pushMetaData) {

            }
        } else {
            emitSocketResponse(SocketResponse.error("No Network Connection", null))
        }
    }


    /**
     * Connects to the socket by credential and using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required fot push calls to work
     * @param credentialConfig, represents a SIP user for login - credential based
     * @param autoLogin, if true, the SDK will automatically log in with
     * the provided credentials on connection established
     * We recommend setting this to true
     *
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        credentialConfig: CredentialConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    ) {
        isSocketDebug = credentialConfig.debug
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(credentialConfig.logLevel, credentialConfig.customLogger)


        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        if (credentialConfig.region != Region.AUTO)
            providedHostAddress = "${credentialConfig.region.value}.$providedHostAddress"

        socket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            Logger.d(message = "Provided Host Address: $providedHostAddress")

            CoroutineScope(Dispatchers.IO).launch {
                if (credentialConfig.fallbackOnRegionFailure) {
                    providedHostAddress = ConnectivityHelper.resolveReachableHost(
                        providedHostAddress!!,
                        providedPort!!
                    )
                    Logger.d(message = "Verified Host Address: $providedHostAddress")
                }

                if (txPushMetaData != null) {
                    val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                    voiceSDKID = metadata.voiceSdkId
                } else if (voiceSDKID != null) {
                    pushMetaData = PushMetaData(
                        callerName = "",
                        callerNumber = "",
                        callId = "",
                        voiceSdkId = voiceSDKID
                    )
                }
                socket.connect(this@TelnyxClient, providedHostAddress, providedPort, pushMetaData) {
                    if (autoLogin) {
                        credentialLogin(credentialConfig)
                    }
                }
            }
        } else {
            emitSocketResponse(SocketResponse.error("No Network Connection", null))
        }
    }

    /**
     * Connects to the socket by token and using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required fot push calls to work
     * @param tokenConfig, represents a SIP user for login - token based
     * @param autoLogin, if true, the SDK will automatically log in with
     * the provided credentials on connection established
     * We recommend setting this to true
     *
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    ) {
        isSocketDebug = tokenConfig.debug
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(tokenConfig.logLevel, tokenConfig.customLogger)

        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        if (tokenConfig.region != Region.AUTO)
            providedHostAddress = "${tokenConfig.region.value}.$providedHostAddress"

        socket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            Logger.d(message = "Provided Host Address: $providedHostAddress")

            CoroutineScope(Dispatchers.IO).launch {
                if (tokenConfig.fallbackOnRegionFailure) {
                    providedHostAddress = ConnectivityHelper.resolveReachableHost(
                        providedHostAddress!!,
                        providedPort!!
                    )
                    Logger.d(message = "Verified Host Address: $providedHostAddress")
                }

                if (txPushMetaData != null) {
                    val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                    voiceSDKID = metadata.voiceSdkId
                } else if (voiceSDKID != null) {
                    pushMetaData = PushMetaData(
                        callerName = "",
                        callerNumber = "",
                        callId = "",
                        voiceSdkId = voiceSDKID
                    )
                }
                socket.connect(this@TelnyxClient, providedHostAddress, providedPort, pushMetaData) {
                    if (autoLogin) {
                        tokenLogin(tokenConfig)
                    }
                }

            }
        } else {
            emitSocketResponse(SocketResponse.error("No Network Connection", null))
        }
    }

    /**
     * Connects to the socket for anonymous authentication (AI assistant connections).
     * This method allows connecting to AI assistants without traditional SIP credentials.
     *
     * @param providedServerConfig The server configuration for connection
     * @param targetId The unique identifier of the target AI assistant
     * @param targetType The type of target (defaults to "ai_assistant")
     * @param targetVersionId Optional version ID of the target
     * @param userVariables Optional user variables to include
     * @param reconnection Whether this is a reconnection attempt (defaults to false)
     */
    fun connectAnonymously(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
        logLevel: LogLevel = LogLevel.NONE,
    ) {
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(logLevel, null)

        providedHostAddress = providedServerConfig.host

        socket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun

        if (ConnectivityHelper.isNetworkEnabled(context)) {
            Logger.d(message = "Provided Host Address: $providedHostAddress")

            CoroutineScope(Dispatchers.IO).launch {
                socket.connect(this@TelnyxClient, providedHostAddress, providedPort, null) {
                    // Perform anonymous login after socket is connected
                    anonymousLogin(
                        targetId = targetId,
                        targetType = targetType,
                        targetVersionId = targetVersionId,
                        userVariables = userVariables,
                        reconnection = reconnection,
                    )
                }
            }
        } else {
            emitSocketResponse(SocketResponse.error("No Network Connection", null))
        }
    }

    /**
     * Connects to the socket with decline_push parameter for background call decline.
     * This method is specifically designed for declining calls without launching the main app.
     *
     * @param providedServerConfig The server configuration for connection
     * @param config The configuration for login (either CredentialConfig or TokenConfig)
     * @param txPushMetaData The push metadata from the notification
     */
    fun connectWithDeclinePush(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        config: TelnyxConfig,
        txPushMetaData: String? = null,
    ) {
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        socket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun

        if (ConnectivityHelper.isNetworkEnabled(context)) {
            Logger.d(message = "Provided Host Address: $providedHostAddress")
            if (txPushMetaData != null) {
                val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                voiceSDKID = metadata.voiceSdkId
            } else if (voiceSDKID != null) {
                pushMetaData = PushMetaData(
                    callerName = "",
                    callerNumber = "",
                    callId = "",
                    voiceSdkId = voiceSDKID
                )
            }
            socket.connect(this, providedHostAddress, providedPort, pushMetaData) {
                when (config) {
                    is CredentialConfig -> {
                        setSDKLogLevel(config.logLevel, config.customLogger)
                        credentialLoginWithDeclinePush(config)
                    }

                    is TokenConfig -> {
                        setSDKLogLevel(config.logLevel, config.customLogger)
                        tokenLoginWithDeclinePush(config)
                    }
                }
            }
        } else {
            emitSocketResponse(SocketResponse.error("No Network Connection", null))
        }
    }


    /**
     * Sets the callOngoing state to true. This can be used to see if the SDK thinks a call is ongoing.
     */
    internal fun callOngoing() {
        socket.callOngoing()
    }

    /**
     * Sets the callOngoing state to false if the [calls] MutableMap is empty
     * @see [calls]
     */
    internal fun callNotOngoing() {
        if (calls.isEmpty()) {
            socket.callNotOngoing()
        }
    }

    /**
     * register network state change callback.
     * @see [ConnectivityManager]
     */
    private fun registerNetworkCallback() {
        context.let {
            ConnectivityHelper.registerNetworkStatusCallback(it, networkCallback)
            isNetworkCallbackRegistered = true
        }
    }

    /**
     * Unregister network state change callback.
     * @see [ConnectivityManager]
     */
    private fun unregisterNetworkCallback() {
        if (isNetworkCallbackRegistered) {
            context.let {
                ConnectivityHelper.unregisterNetworkStatusCallback(it, networkCallback)
                isNetworkCallbackRegistered = false
            }
        }
    }

    /**
     * Returns the socket response in the form of LiveData (deprecated)
     * The format of each message is provided in SocketResponse and ReceivedMessageBody
     * @see [SocketResponse]
     * @see [ReceivedMessageBody]
     * @deprecated Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData

    /**
     * Returns the  json messages from socket in the form of LiveData used for debugging purposes
     * @deprecated Use wsMessageResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use wsMessagesResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    fun getWsMessageResponse(): LiveData<JsonObject> = wsMessagesResponseLiveData

    /**
     * Returns all active calls that have been stored in our calls MutableMap
     * The MutableMap is converted into a Map - preventing any changes by the SDK User
     *
     * @see [calls]
     */
    fun getActiveCalls(): Map<UUID, Call> {
        return calls.toMap()
    }

    /**
     * Logs the user in with credentials provided via CredentialConfig
     *
     * @param config, the CredentialConfig used to log in
     * @see [CredentialConfig]
     */
    @Deprecated("telnyxclient.credentialLogin is deprecated. Use telnyxclient.connect(..) instead.")
    fun credentialLogin(config: CredentialConfig) {

        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        credentialSessionConfig = config

        isSocketDebug = config.debug

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                loginToken = null,
                login = user,
                passwd = password,
                userVariables = notificationJsonObject,
                loginParams = mapOf("attach_call" to "true"),
                sessid = sessid
            )
        )
        Logger.d(message = "Auto login with credentialConfig")

        socket.send(loginMessage)
    }


    /**
     * Disables push notifications for current logged in user.
     *
     * NB : Push Notifications are enabled by default after login
     *
     * returns : {"jsonrpc":"2.0","id":"","result":{"message":"disable push notification success"}}
     * */
    fun disablePushNotification() {
        val storedConfig = credentialSessionConfig ?: tokenSessionConfig ?: return

        val params = when (storedConfig) {
            is CredentialConfig -> {
                DisablePushParams(
                    user = storedConfig.sipUser,
                    userVariables = UserVariables(storedConfig.fcmToken ?: "")
                )
            }

            is TokenConfig -> {
                TokenDisablePushParams(
                    loginToken = storedConfig.sipToken,
                    userVariables = UserVariables(storedConfig.fcmToken ?: "")
                )
            }
        }

        val disablePushMessage = SendingMessageBody(
            id = UUID.randomUUID().toString(),
            method = SocketMethod.DISABLE_PUSH.methodName,
            params = params
        )
        val message = Gson().toJson(disablePushMessage)
        Logger.d("disablePushMessage", message)
        socket.send(disablePushMessage)
    }


    private var attachCallId: String? = null

    /**
     * Attaches push notifications to current call invite.
     * Backend responds with INVITE message
     * */
    private fun attachCall() {

        attachCallId = UUID.randomUUID().toString()
        val params = AttachCallParams(
            userVariables = AttachUserVariables()
        )

        val attachPushMessage = SendingMessageBody(
            id = attachCallId!!,
            method = SocketMethod.ATTACH_CALL.methodName,
            params = params
        )
        Logger.d("sending attach Call", attachPushMessage.toString())
        socket.send(attachPushMessage)
        //reset push params
        pushMetaData = null
        isCallPendingFromPush = false
    }


    /**
     * Logs the user in with credentials provided via TokenConfig
     *
     * @param config, the TokenConfig used to log in
     * @see [TokenConfig]
     */
    @Deprecated(
        "telnyxclient.tokenLogin is deprecated. Use telnyxclient.connect(...,autoLogin:true) " +
                "with autoLogin set to true instead."
    )
    fun tokenLogin(config: TokenConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        tokenSessionConfig = config

        isSocketDebug = config.debug

        setSDKLogLevel(logLevel, customLogger)

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                loginToken = token,
                login = null,
                passwd = null,
                userVariables = notificationJsonObject,
                loginParams = mapOf("attach_calls" to "true"),
                sessid = sessid
            )
        )
        socket.send(loginMessage)
    }

    /**
     * Performs anonymous login for AI assistant connections.
     * This method allows connecting to AI assistants without traditional SIP credentials.
     *
     * @param targetId the unique identifier of the target AI assistant
     * @param targetType the type of target (defaults to "ai_assistant")
     * @param targetVersionId optional version ID of the target
     * @param userVariables optional user variables to include
     * @param reconnection whether this is a reconnection attempt (defaults to false)
     */
    fun anonymousLogin(
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
    ) {
        val uuid: String = UUID.randomUUID().toString()

        val userAgent = UserAgent(
            sdkVersion = SDK_VERSION,
            data = "Android-$SDK_VERSION"
        )

        val anonymousLoginParams = AnonymousLoginParams(
            targetType = targetType,
            targetId = targetId,
            targetVersionId = targetVersionId,
            userVariables = userVariables,
            reconnection = reconnection,
            userAgent = userAgent,
            sessid = sessid
        )

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.ANONYMOUS_LOGIN.methodName,
            params = anonymousLoginParams
        )

        Logger.d(message = "Anonymous Login Message: ${Gson().toJson(loginMessage)}")
        socket.send(loginMessage)
    }

    fun sendAIAssistantMessage(
        message: String
    ) {
        val uuid: String = UUID.randomUUID().toString()

        val conversationContent = ConversationContent(
            type = "input_text",
            text = message
        )

        val conversationItem = ConversationItem(
            id = UUID.randomUUID().toString(),
            type = "message",
            role = "user",
            content = listOf(conversationContent)
        )

        val aiConversationParams = AiConversationParams(
            type = "conversation.item.create",
            item = conversationItem
        )

        val aiConversationMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.AI_CONVERSATION.methodName,
            params = aiConversationParams
        )

        Logger.d(message = "AI Conversation Message: ${Gson().toJson(aiConversationMessage)}")
        socket.send(aiConversationMessage)
    }

    /**
     * Performs credential login with decline_push parameter for background call decline.
     * This method sends a login message with decline_push set to true.
     *
     * @param config The credential configuration for login
     */
    private fun credentialLoginWithDeclinePush(config: CredentialConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        credentialSessionConfig = config

        isSocketDebug = config.debug

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                loginToken = null,
                login = user,
                passwd = password,
                userVariables = notificationJsonObject,
                loginParams = mapOf("decline_push" to "true"),
                sessid = sessid
            )
        )
        Logger.d(message = "Auto login with credentialConfig for decline push")

        socket.send(loginMessage)
    }

    /**
     * Performs token login with decline_push parameter for background call decline.
     * This method sends a login message with decline_push set to true.
     *
     * @param config The token configuration for login
     */
    private fun tokenLoginWithDeclinePush(config: TokenConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        tokenSessionConfig = config

        isSocketDebug = config.debug

        setSDKLogLevel(logLevel, customLogger)

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                loginToken = token,
                login = null,
                passwd = null,
                userVariables = notificationJsonObject,
                loginParams = mapOf("decline_push" to "true"),
                sessid = sessid
            )
        )
        Logger.d(message = "Auto login with tokenConfig for decline push")

        socket.send(loginMessage)
    }


    /**
     * Sets the global SDK log level
     * Logging is implemented with the Logger provided via the [Config],
     * if none is provided then the default logger in [TxLogger] is used
     *
     * @param logLevel The LogLevel specified for the SDK
     * @param customLogger Optional custom logger implementation
     * @see [LogLevel]
     * @see [TxLogger]
     */
    private fun setSDKLogLevel(logLevel: LogLevel, customLogger: TxLogger? = null) {
        Logger.init(logLevel, customLogger)
    }

    /**
     * Returns a MutableList of available audio devices
     * Audio devices are represented by their Int reference ids
     *
     * @return [MutableList] of [Int]
     */
    private fun getAvailableAudioOutputTypes(): MutableList<Int> {
        val availableTypes: MutableList<Int> = mutableListOf()
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach {
            availableTypes.add(it.type)
        }
        return availableTypes
    }

    /**
     * Sets the audio device that the SDK should use
     *
     * @param audioDevice, the chosen [AudioDevice] to be used by the SDK
     * @see [AudioDevice]
     */
    fun setAudioOutputDevice(audioDevice: AudioDevice) {
        val availableTypes = getAvailableAudioOutputTypes()
        when (audioDevice) {
            AudioDevice.BLUETOOTH -> {
                if (availableTypes.contains(AudioDevice.BLUETOOTH.code)) {
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager?.startBluetoothSco()
                    audioManager?.isBluetoothScoOn = true
                } else {
                    Logger.d(
                        message = Logger.formatMessage(
                            "[%s] :: No Bluetooth device detected",
                            this@TelnyxClient.javaClass.simpleName
                        )
                    )
                }
            }

            AudioDevice.PHONE_EARPIECE -> {
                // For phone ear piece
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
                audioManager?.isSpeakerphoneOn = false
            }

            AudioDevice.LOUDSPEAKER -> {
                // For phone speaker(loudspeaker)
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.stopBluetoothSco()
                audioManager?.isBluetoothScoOn = false
                audioManager?.isSpeakerphoneOn = true
            }
        }
    }


    /**
     * Use MediaPlayer to play the audio of the saved user Ringtone
     * If no ringtone was provided, we print a relevant message
     *
     * @see [MediaPlayer]
     */
    internal fun playRingtone() {
        // set speakerState to current audioManager settings
        speakerState = if (speakerState != UNASSIGNED) {
            if (audioManager?.isSpeakerphoneOn == true) {
                SpeakerMode.SPEAKER
            } else {
                SpeakerMode.EARPIECE
            }
        } else {
            SpeakerMode.EARPIECE
        }

        // set audioManager to ringtone settings
        audioManager?.mode = AudioManager.MODE_RINGTONE
        audioManager?.isSpeakerphoneOn = true

        rawRingtone?.let {
            stopMediaPlayer()
            try {

                if (it.getRingtoneType() == RingtoneType.URI) {
                    mediaPlayer = MediaPlayer.create(context, it as Uri)
                } else if (it.getRingtoneType() == RingtoneType.RAW) {
                    mediaPlayer = MediaPlayer.create(context, it as Int)
                }
                mediaPlayer ?: kotlin.run {
                    Logger.d(message = "Ringtone not valid:: No ringtone will be played")
                    return
                }
                mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                if (mediaPlayer?.isPlaying == false) {
                    mediaPlayer!!.start()
                    mediaPlayer!!.isLooping = true
                }
                Logger.d(message = "Ringtone playing")
            } catch (e: TypeCastException) {
                Logger.e(message = "Exception: ${e.message}")
            }
        } ?: run {
            Logger.d(message = "No ringtone specified :: No ringtone will be played")
        }
    }

    private fun setSpeakerMode(speakerMode: SpeakerMode) {
        when (speakerMode) {
            SpeakerMode.SPEAKER -> {
                audioManager?.isSpeakerphoneOn = true
            }

            SpeakerMode.EARPIECE -> {
                audioManager?.isSpeakerphoneOn = false
            }

            UNASSIGNED -> audioManager?.isSpeakerphoneOn = false
        }
    }

    private fun Any?.getRingtoneType(): RingtoneType? {
        return when (this) {
            is Uri -> RingtoneType.URI
            is Int -> RingtoneType.RAW
            else -> null
        }
    }

    /**
     * Use MediaPlayer to play the audio of the saved user Ringback tone
     * If no ringback tone was provided, we print a relevant message
     *
     * @see [MediaPlayer]
     */
    private fun playRingBackTone() {
        rawRingbackTone?.let {
            stopMediaPlayer()
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            if (mediaPlayer?.isPlaying == false) {
                mediaPlayer!!.start()
                mediaPlayer!!.isLooping = true
            }
        } ?: run {
            Logger.d(message = "No ringtone specified :: No ringtone will be played")
        }
    }

    /**
     * Stops any audio that the MediaPlayer is playing
     * @see [MediaPlayer]
     */
    private fun stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        Logger.d(message = "ringtone/ringback media player stopped and released")

        // reset audio mode to communication
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun requestGatewayStatus() {
        if (waitingForReg) {
            socket.send(
                SendingMessageBody(
                    id = UUID.randomUUID().toString(),
                    method = SocketMethod.GATEWAY_STATE.methodName,
                    params = StateParams(
                        state = null
                    )
                )
            )
        }
    }

    /**
     * Fires once we have successfully received a 'REGED' gateway response, meaning login was successful
     * @param receivedLoginSessionId, the session ID of the successfully registered session.
     */
    internal fun onLoginSuccessful(receivedLoginSessionId: String) {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: onLoginSuccessful :: [%s] :: Ready to make calls",
                this@TelnyxClient.javaClass.simpleName,
                receivedLoginSessionId
            )
        )
        sessid = receivedLoginSessionId
        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.LOGIN.methodName,
                    LoginResponse(receivedLoginSessionId)
                )
            )
        )

        socket.isLoggedIn = true

        Logger.d(message = "isCallPendingFromPush $isCallPendingFromPush")
        //if there is a call pending from push, attach it
        if (isCallPendingFromPush) {
            attachCall()
        }

        CoroutineScope(Dispatchers.Main).launch {
            emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.CLIENT_READY.methodName,
                        null
                    )
                )
            )
        }
    }

    // TxSocketListener Overrides
    override fun onClientReady(jsonObject: JsonObject) {
        val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
        if (voiceSdkID != null) {
            Logger.d(message = "Voice SDK ID _ $voiceSdkID")
            this@TelnyxClient.voiceSDKID = voiceSdkID
        } else {
            Logger.e(message = "No Voice SDK ID")
        }

        if (gatewayState != GatewayState.REGED.state) {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: onClientReady :: retrieving gateway state",
                    this@TelnyxClient.javaClass.simpleName
                )
            )
            if (waitingForReg) {
                requestGatewayStatus()
                gatewayResponseTimer = Timer()
                gatewayResponseTimer?.schedule(
                    timerTask {
                        if (registrationRetryCounter < RETRY_REGISTER_TIME) {
                            if (waitingForReg) {
                                onClientReady(jsonObject)
                            }
                            registrationRetryCounter++
                        } else {
                            Logger.d(
                                message = Logger.formatMessage(
                                    "[%s] :: Gateway registration has timed out",
                                    this@TelnyxClient.javaClass.simpleName
                                )
                            )
                            emitSocketResponse(
                                SocketResponse.error(
                                    "Gateway registration has timed out",
                                    SocketError.GATEWAY_TIMEOUT_ERROR.errorCode
                                )
                            )
                        }
                    },
                    GATEWAY_RESPONSE_DELAY
                )
            }
        } else {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: onClientReady :: Ready to make calls",
                    this@TelnyxClient.javaClass.simpleName
                )
            )

            emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.CLIENT_READY.methodName,
                        null
                    )
                )
            )
        }
    }

    override fun onGatewayStateReceived(gatewayState: String, receivedSessionId: String?) {
        when (gatewayState) {
            GatewayState.REGED.state -> {
                invalidateGatewayResponseTimer()
                waitingForReg = false
                receivedSessionId?.let {
                    resetGatewayCounters()
                    onLoginSuccessful(it)
                } ?: kotlin.run {
                    resetGatewayCounters()
                    onLoginSuccessful(sessid)
                }
            }


            GatewayState.NOREG.state -> {
                invalidateGatewayResponseTimer()
                emitSocketResponse(
                    SocketResponse.error(
                        "Gateway registration has timed out",
                        SocketError.GATEWAY_TIMEOUT_ERROR.errorCode
                    )
                )
            }

            GatewayState.FAILED.state -> {
                invalidateGatewayResponseTimer()
                emitSocketResponse(
                    SocketResponse.error(
                        "Gateway registration has failed",
                        SocketError.GATEWAY_FAILURE_ERROR.errorCode
                    )
                )
            }

            (GatewayState.FAIL_WAIT.state), (GatewayState.DOWN.state) -> {
                if (autoReconnectLogin && connectRetryCounter < RETRY_CONNECT_TIME) {
                    connectRetryCounter++
                    Logger.d(
                        message = Logger.formatMessage(
                            "[%s] :: Attempting reconnection :: attempt $connectRetryCounter / $RETRY_CONNECT_TIME",
                            this@TelnyxClient.javaClass.simpleName
                        )
                    )
                    runBlocking { reconnectToSocket() }
                } else {
                    invalidateGatewayResponseTimer()
                    emitSocketResponse(
                        SocketResponse.error(
                            "Gateway registration has received fail wait response",
                            SocketError.GATEWAY_FAILURE_ERROR.errorCode
                        )
                    )
                }
            }

            GatewayState.EXPIRED.state -> {
                invalidateGatewayResponseTimer()
                emitSocketResponse(
                    SocketResponse.error(
                        "Gateway registration has timed out",
                        SocketError.GATEWAY_TIMEOUT_ERROR.errorCode
                    )
                )
            }

            GatewayState.UNREGED.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.TRYING.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.REGISTER.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.UNREGISTER.state -> {
                // NOOP - logged within TxSocket
            }

            else -> {
                invalidateGatewayResponseTimer()
                emitSocketResponse(
                    SocketResponse.error(
                        "Gateway registration has failed with an unknown error",
                        null
                    )
                )
            }
        }
    }

    private fun invalidateGatewayResponseTimer() {
        gatewayResponseTimer?.cancel()
        gatewayResponseTimer?.purge()
        gatewayResponseTimer = null
    }

    private fun resetGatewayCounters() {
        registrationRetryCounter = 0
        connectRetryCounter = 0
    }

    /**
     * Starts the reconnection timer to track reconnection attempts.
     * If reconnection takes longer than RECONNECT_TIMEOUT, it will trigger an error.
     */
    private fun startReconnectionTimer() {
        Logger.d(message = "Starting reconnection timer")
        // Cancel any existing timer
        reconnectTimeOutJob?.cancel()
        reconnectTimeOutJob = null
        // Create a new timer to check for timeout
        reconnectTimeOutJob = CoroutineScope(Dispatchers.Default).launch {
            delay(
                credentialSessionConfig?.reconnectionTimeout
                    ?: tokenSessionConfig?.reconnectionTimeout ?: RECONNECT_TIMEOUT
            )
            if (reconnecting) {
                Logger.d(message = "Reconnection timeout reached after ${RECONNECT_TIMEOUT}ms")
                reconnecting = false
                // Handle the timeout by updating call states and notifying the user
                Handler(Looper.getMainLooper()).post {
                    getActiveCalls().forEach { (_, call) ->
                        call.setReconnectionTimeout()
                    }
                    emitSocketResponse(
                        SocketResponse.error(
                            "Reconnection timeout after ${RECONNECT_TIMEOUT / TIMEOUT_DIVISOR} seconds",
                            null
                        )
                    )

                    // Reset reconnection state
                    reconnecting = false
                    cancelReconnectionTimer()
                }
            } else {
                // If we're no longer reconnecting, cancel the timer
                cancelReconnectionTimer()
            }
        }
    }

    /**
     * Cancels the reconnection timer if it's running.
     */
    private fun cancelReconnectionTimer() {
        Logger.d(message = "Cancelling reconnection timer")
        reconnectTimeOutJob?.cancel()
        reconnectTimeOutJob = null
    }

    override fun onConnectionEstablished() {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: onConnectionEstablished",
                this@TelnyxClient.javaClass.simpleName
            )
        )
        emitSocketResponse(SocketResponse.established())

    }

    override fun onErrorReceived(jsonObject: JsonObject, errorCode: Int?) {
        val id = jsonObject.get("id").asString
        if (errorCode == null && attachCallId == id) {
            Logger.d(message = "Call Failed Error Received")
            emitSocketResponse(SocketResponse.error("Call Failed", null))
            return
        }
        val errorMessage = jsonObject.get("error").asJsonObject.get("message").asString
        Logger.d(message = "onErrorReceived $errorMessage, code: $errorCode")
        emitSocketResponse(SocketResponse.error(errorMessage, errorCode))
    }

    override fun onByeReceived(jsonObject: JsonObject) {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: onByeReceived JSON: %s",
                this.javaClass.simpleName,
                jsonObject.toString()
            )
        )

        try {
            val params = jsonObject.getAsJsonObject("params")
            val callIdString = params?.get("callID")?.asString

            if (callIdString == null) {
                Logger.e(message = "Received BYE without callID in params: $jsonObject")
                return
            }
            val callId = UUID.fromString(callIdString)

            val cause = params.get("cause")?.asString
            val causeCode = params.get("causeCode")?.asInt
            val sipCode = params.get("sipCode")?.asInt
            val sipReason = params.get("sipReason")?.asString

            val byeCall = calls[callId]
            byeCall?.apply {
                val terminationReason = CallTerminationReason(
                    cause = cause,
                    causeCode = causeCode,
                    sipCode = sipCode,
                    sipReason = sipReason
                )
                updateCallState(CallState.DONE(terminationReason))

                // Create the rich ByeResponse to be sent to the UI/ViewModel
                val byeResponseForUi = com.telnyx.webrtc.sdk.verto.receive.ByeResponse(
                    callId = callId,
                    cause = cause,
                    causeCode = causeCode,
                    sipCode = sipCode,
                    sipReason = sipReason
                )

                client.emitSocketResponse(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.BYE.methodName,
                            byeResponseForUi
                        )
                    )
                )

                // Existing cleanup logic
                removeWebRTCReporter(callId)?.stopStats()

                client.removeFromCalls(callId)
                client.callNotOngoing()
                resetCallOptions()
                client.stopMediaPlayer()
                peerConnection?.release()
                peerConnection = null
                answerResponse = null
                inviteResponse = null
            } ?: run {
                Logger.w(message = "Received BYE for a callId not found in active calls: $callId")
            }
        } catch (e: Exception) {
            Logger.e(message = "Error processing onByeReceived: ${e.message}")
        }
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val answeredCall = calls[UUID.fromString(callId)]
        answeredCall?.apply {
            val customHeaders =
                params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

            when {
                params.has("sdp") -> {
                    val stringSdp = params.get("sdp").asString
                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

                    peerConnection?.onRemoteSessionReceived(sdp)

                    updateCallState(CallState.ACTIVE)

                    val answerResponse = AnswerResponse(
                        UUID.fromString(callId),
                        stringSdp,
                        customHeaders?.toCustomHeaders() ?: arrayListOf()
                    )
                    this.answerResponse = answerResponse
                    client.emitSocketResponse(
                        SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                SocketMethod.ANSWER.methodName,
                                answerResponse
                            )
                        )
                    )
                }

                earlySDP -> {
                    updateCallState(CallState.CONNECTING)
                    val stringSdp = peerConnection?.getLocalDescription()?.description
                    val answerResponse = AnswerResponse(
                        UUID.fromString(callId),
                        stringSdp!!,
                        customHeaders?.toCustomHeaders() ?: arrayListOf()
                    )
                    this.answerResponse = answerResponse
                    client.emitSocketResponse(
                        SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                SocketMethod.ANSWER.methodName,
                                answerResponse
                            )
                        )
                    )
                    updateCallState(CallState.ACTIVE)
                }

                else -> {
                    // There was no SDP in the response, there was an error.
                    val reason = CallTerminationReason(
                        cause = "AnswerError",
                        sipReason = "No SDP in answer response"
                    )
                    updateCallState(CallState.DONE(reason))
                    client.removeFromCalls(UUID.fromString(callId))
                }
            }
            client.callOngoing()
            client.stopMediaPlayer()
        }
        answeredCall?.let {
            addToCalls(it)
        }
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val mediaCall = calls[UUID.fromString(callId)]
        mediaCall?.apply {
            if (params.has("sdp")) {
                val stringSdp = params.get("sdp").asString
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

                peerConnection?.onRemoteSessionReceived(sdp)
                // Set internal flag for early retrieval of SDP -
                // generally occurs when a ringback setting is applied in inbound call settings
                earlySDP = true

                val callerIDName =
                    if (params.has("caller_id_name")) params.get("caller_id_name").asString else ""
                val callerNumber =
                    if (params.has("caller_id_number")) params.get("caller_id_number").asString else ""

                val mediaResponse = MediaResponse(
                    UUID.fromString(callId),
                    callerIDName,
                    callerNumber,
                    sessionId,
                )
                client.emitSocketResponse(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.MEDIA.methodName,
                            mediaResponse
                        )
                    )
                )

            } else {
                // There was no SDP in the response, there was an error.
                val reason = CallTerminationReason(
                    cause = "MediaError",
                    sipReason = "No SDP in media response"
                )
                updateCallState(CallState.DONE(reason))
                client.removeFromCalls(UUID.fromString(callId))
            }

        }


        /*Stop local Media and play ringback from telnyx cloud*/
        stopMediaPlayer()
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        if (jsonObject.has("params")) {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: onOfferReceived [%s]",
                    this@TelnyxClient.javaClass.simpleName,
                    jsonObject
                )
            )
            val offerCall = call!!.copy(
                context = context,
                client = this,
                socket = socket,
                sessionId = sessid,
                audioManager = audioManager!!,
                providedTurn = providedTurn!!,
                providedStun = providedStun!!
            ).apply {

                val params = jsonObject.getAsJsonObject("params")
                val offerCallId = UUID.fromString(params.get("callID").asString)
                val remoteSdp = params.get("sdp").asString
                val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
                if (voiceSdkID != null) {
                    Logger.d(message = "Voice SDK ID _ $voiceSdkID")
                    this@TelnyxClient.voiceSDKID = voiceSdkID
                } else {
                    Logger.e(message = "No Voice SDK ID")
                }

                val callerName = params.get("caller_id_name").asString
                val callerNumber = params.get("caller_id_number").asString
                telnyxSessionId = UUID.fromString(params.get("telnyx_session_id").asString)
                telnyxLegId = UUID.fromString(params.get("telnyx_leg_id").asString)

                // Set global callID
                callId = offerCallId
                val call = this


                //retrieve custom headers
                val customHeaders =
                    params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

                peerConnection = Peer(
                    context,
                    client,
                    providedTurn,
                    providedStun,
                    offerCallId,
                    prefetchIceCandidates,
                    getForceRelayCandidate()
                ) { candidate ->
                    addIceCandidateInternal(candidate)
                }.also {
                    // Check the global debug flag here for incoming calls where per-call isn't set yet
                    if (isSocketDebug) {
                        val webRTCReporter = WebRTCReporter(
                            socket,
                            callId,
                            telnyxLegId?.toString(),
                            it,
                            false,
                            isSocketDebug
                        )
                        webRTCReporter.onCallQualityChange = { metrics ->
                            onCallQualityChange?.invoke(metrics)
                        }
                        webRTCReporter.startStats()
                        addWebRTCReporter(callId, webRTCReporter)
                    }
                }

                peerConnection?.startLocalAudioCapture()

                peerConnection?.onRemoteSessionReceived(
                    SessionDescription(
                        SessionDescription.Type.OFFER,
                        remoteSdp
                    )
                )

                peerConnection?.answer(AppSdpObserver())

                val inviteResponse = InviteResponse(
                    callId,
                    remoteSdp,
                    callerName,
                    callerNumber,
                    sessionId,
                    customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf()
                )
                this.inviteResponse = inviteResponse

            }
            offerCall.client.playRingtone()
            addToCalls(offerCall)
            offerCall.client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.INVITE.methodName,
                        offerCall.inviteResponse
                    )
                )
            )
        } else {
            Logger.d(
                message = Logger.formatMessage(
                    "[%s] :: Invalid offer received, missing required parameters [%s]",
                    this.javaClass.simpleName, jsonObject
                )
            )
        }

    }

    fun isSocketConnected(): Boolean {
        return socket.isConnected
    }

    override fun onRingingReceived(jsonObject: JsonObject) {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: onRingingReceived [%s]",
                this@TelnyxClient.javaClass.simpleName,
                jsonObject
            )
        )
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val ringingCall = calls[UUID.fromString(callId)]

        ringingCall?.apply {
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
            val customHeaders =
                params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

            val ringingResponse = RingingResponse(
                UUID.fromString(callId),
                params.get("caller_id_name").asString,
                params.get("caller_id_number").asString,
                sessionId,
                customHeaders?.toCustomHeaders() ?: arrayListOf()
            )
            client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.RINGING.methodName,
                        ringingResponse
                    )
                )
            )
        }
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        call?.apply {
            updateCallState(CallState.CONNECTING)
        }
    }

    override fun onDisablePushReceived(jsonObject: JsonObject) {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: onDisablePushReceived [%s]",
                this@TelnyxClient.javaClass.simpleName,
                jsonObject
            )
        )
        val errorMessage = jsonObject.get("result").asJsonObject.get("message").asString
        val disablePushResponse = DisablePushResponse(
            errorMessage.contains(DisablePushResponse.SUCCESS_KEY),
            errorMessage
        )
        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.RINGING.methodName,
                    disablePushResponse
                )
            )
        )
    }

    override fun onAttachReceived(jsonObject: JsonObject) {
        // reset reconnecting state
        reconnecting = false
        val params = jsonObject.getAsJsonObject("params")
        val offerCallId = UUID.fromString(params.get("callID").asString)

        calls[offerCallId]?.copy(
            context = context,
            client = this,
            socket = socket,
            sessionId = sessid,
            audioManager = audioManager!!,
            providedTurn = providedTurn!!,
            providedStun = providedStun!!,
            mutableCallStateFlow = calls[offerCallId]!!.mutableCallStateFlow,
        )?.apply {

            val remoteSdp = params.get("sdp").asString
            val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
            if (voiceSdkID != null) {
                Logger.d(message = "Voice SDK ID _ $voiceSdkID")
                this@TelnyxClient.voiceSDKID = voiceSdkID
            } else {
                Logger.e(message = "No Voice SDK ID")
            }

            // val callerName = params.get("caller_id_name").asString
            val callerNumber = params.get("caller_id_number").asString
            telnyxSessionId = UUID.fromString(params.get("telnyx_session_id").asString)
            telnyxLegId = UUID.fromString(params.get("telnyx_leg_id").asString)

            // Set global callID
            callId = offerCallId


            peerConnection = Peer(
                context,
                client,
                providedTurn,
                providedStun,
                offerCallId,
                prefetchIceCandidates,
                getForceRelayCandidate()
            ).also {
                // Check the global debug flag here for reattach scenarios
                if (isSocketDebug) {
                    val webRTCReporter = WebRTCReporter(
                        socket,
                        callId,
                        telnyxLegId?.toString(),
                        it,
                        false,
                        isSocketDebug
                    )
                    webRTCReporter.onCallQualityChange = { metrics ->
                        onCallQualityChange?.invoke(metrics)
                    }
                    webRTCReporter.startStats()
                    addWebRTCReporter(callId, webRTCReporter)
                }
            }

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
                Call.ICE_CANDIDATE_DELAY
            )
            calls[this.callId]?.updateCallState(CallState.ACTIVE)
            this.updateCallState(calls[this.callId]?.callStateFlow?.value ?: CallState.ACTIVE)
            calls[this.callId] = this.apply {
                updateCallState(CallState.ACTIVE)
            }
        } ?: run {
            Logger.e(message = "Call not found for Attach")
        }
    }

    override fun setCallRecovering() {
        call?.setCallRecovering()
    }

    override fun pingPong() {
        Logger.d(
            message = Logger.formatMessage(
                "[%s] :: pingPong ",
                this@TelnyxClient.javaClass.simpleName
            )
        )
    }

    internal fun onRemoteSessionErrorReceived(errorMessage: String?) {
        stopMediaPlayer()
        errorMessage?.let { emitSocketResponse(SocketResponse.error(it)) }
    }

    /**
     * Disconnect from the TxSocket and unregister the provided network callback
     *
     * @see [ConnectivityHelper]
     * @see [TxSocket]
     */
    override fun onDisconnect() {
        emitSocketResponse(SocketResponse.disconnect())
        invalidateGatewayResponseTimer()
        resetGatewayCounters()
        unregisterNetworkCallback()
        socket.destroy()
    }

    /**
     * Handles AI conversation messages received from the socket
     * Processes transcript updates and widget settings
     *
     * @param jsonObject the socket response containing AI conversation data
     */
    override fun onAiConversationReceived(jsonObject: JsonObject) {
        Logger.i(message = "AI CONVERSATION RECEIVED :: $jsonObject")

        try {
            val aiConversationResponse =
                Gson().fromJson(jsonObject, AiConversationResponse::class.java)
            val params = aiConversationResponse.aiConversationParams

            // Store widget settings if available
            params?.widgetSettings?.let { settings ->
                _currentWidgetSettings = settings
                Logger.i(message = "Widget settings updated :: $_currentWidgetSettings")
            }

            // Process message for transcript extraction
            processAiConversationForTranscript(params)

            // Emit socket response
            val receivedMessageBody = ReceivedMessageBody(
                method = SocketMethod.AI_CONVERSATION.methodName,
                result = aiConversationResponse
            )
            emitSocketResponse(SocketResponse.aiConversation(receivedMessageBody))

        } catch (e: Exception) {
            Logger.e(message = "Error processing AI conversation message: ${e.message}")
        }
    }

    /**
     * Process AI conversation messages for transcript extraction
     */
    private fun processAiConversationForTranscript(params: AiConversationParams?) {
        if (params?.type == null) return

        when (params.type) {
            "conversation.item.created" -> handleConversationItemCreated(params)
            "response.text.delta" -> handleResponseTextDelta(params)
            // Other AI conversation message types are ignored for transcript
        }
    }

    /**
     * Handle user speech transcript from conversation.item.created messages
     */
    private fun handleConversationItemCreated(params: AiConversationParams) {
        val item = params.item
        if (item?.role != TranscriptItem.ROLE_USER || item.status != "completed") {
            return // Only handle completed user messages
        }

        val content = item.content
            ?.mapNotNull { it.transcript ?: it.text }
            ?.joinToString(" ") ?: ""

        if (content.isNotEmpty() && item.id != null) {
            val transcriptItem = TranscriptItem(
                id = item.id,
                role = TranscriptItem.ROLE_USER,
                content = content,
                timestamp = Date()
            )

            _transcript.add(transcriptItem)
            _transcriptUpdateFlow.tryEmit(_transcript.toList())
        }
    }

    /**
     * Handle AI response text deltas from response.text.delta messages
     */
    private fun handleResponseTextDelta(params: AiConversationParams) {
        val delta = params.delta ?: return
        val itemId = params.itemId ?: return

        // Initialize buffer for this response if not exists
        if (!assistantResponseBuffers.containsKey(itemId)) {
            assistantResponseBuffers[itemId] = StringBuilder()
        }
        assistantResponseBuffers[itemId]?.append(delta)

        // Create or update transcript item for this response
        val existingIndex = _transcript.indexOfFirst { it.id == itemId }
        val currentContent = assistantResponseBuffers[itemId]?.toString() ?: ""

        if (existingIndex >= 0) {
            // Update existing transcript item with accumulated content
            _transcript[existingIndex] = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = _transcript[existingIndex].timestamp,
                isPartial = true
            )
        } else {
            // Create new transcript item
            val transcriptItem = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = Date(),
                isPartial = true
            )
            _transcript.add(transcriptItem)
        }

        _transcriptUpdateFlow.tryEmit(_transcript.toList())
    }

    /**
     * Gets the forceRelayCandidate setting from the current session config
     */
    private fun getForceRelayCandidate(): Boolean {
        return credentialSessionConfig?.forceRelayCandidate
            ?: tokenSessionConfig?.forceRelayCandidate
            ?: false
    }

    /**
     * Returns a list of supported audio codecs available on the device.
     * This method queries the device's MediaCodecList to find all available audio encoders
     * and returns them in a format compatible with the preferred_codecs parameter.
     *
     * @return List of [AudioCodec] objects representing the supported audio codecs
     */
    fun getSupportedAudioCodecs(): List<AudioCodec> {
        val supportedCodecs = mutableListOf<AudioCodec>()
        
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (codecInfo in codecList.codecInfos) {
                if (!codecInfo.isEncoder) {
                    continue
                }

                for (type in codecInfo.supportedTypes) {
                    if (type.startsWith("audio/")) {
                        Logger.d(message = "Supported audio codec: ${codecInfo.name}, type: $type")
                        
                        val audioCodec = mapTypeToAudioCodec(type)
                        
                        // Avoid duplicates
                        if (!supportedCodecs.any { it.mimeType == audioCodec.mimeType }) {
                            supportedCodecs.add(audioCodec)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(message = "Error retrieving supported audio codecs: ${e.message}")
        }
        
        return supportedCodecs
    }

    /**
     * Maps a codec type string to an AudioCodec object with appropriate settings.
     * 
     * @param type The codec type string (e.g., "audio/opus")
     * @return AudioCodec object configured for the given type
     */
    private fun mapTypeToAudioCodec(type: String): AudioCodec {
        return when {
            type.contains("opus", ignoreCase = true) -> {
                AudioCodec(
                    channels = 2,
                    clockRate = 48000,
                    mimeType = "audio/opus",
                    sdpFmtpLine = "minptime=10;useinbandfec=1"
                )
            }
            type.contains("pcma", ignoreCase = true) || type.contains("g711a", ignoreCase = true) -> {
                AudioCodec(
                    channels = 1,
                    clockRate = 8000,
                    mimeType = "audio/PCMA"
                )
            }
            type.contains("pcmu", ignoreCase = true) || type.contains("g711u", ignoreCase = true) -> {
                AudioCodec(
                    channels = 1,
                    clockRate = 8000,
                    mimeType = "audio/PCMU"
                )
            }
            type.contains("g722", ignoreCase = true) -> {
                AudioCodec(
                    channels = 1,
                    clockRate = 16000,
                    mimeType = "audio/G722"
                )
            }
            type.contains("g729", ignoreCase = true) -> {
                AudioCodec(
                    channels = 1,
                    clockRate = 8000,
                    mimeType = "audio/G729"
                )
            }
            else -> {
                AudioCodec(
                    channels = 1,
                    clockRate = 8000,
                    mimeType = type
                )
            }
        }
    }

    /**
     * Disconnects the TelnyxClient, resets all internal states, and stops any ongoing audio playback.
     * This method should be called when the client is no longer needed or when the user logs out.
     */
    fun disconnect() {
        Logger.d(message = "Disconnecting TelnyxClient and clearing states")
        onDisconnect()
    }

    private fun addWebRTCReporter(callId: UUID, webRTCReporter: WebRTCReporter) {
        webRTCReportersMap[callId] = webRTCReporter
    }

    private fun removeWebRTCReporter(callId: UUID): WebRTCReporter? {
        return webRTCReportersMap.remove(callId)
    }

    private fun getWebRTCReporter(callId: UUID): WebRTCReporter? {
        return webRTCReportersMap[callId]
    }
}
