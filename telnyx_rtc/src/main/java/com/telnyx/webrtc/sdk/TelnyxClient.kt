/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.bugsnag.android.Bugsnag
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.peer.PeerConnectionObserver
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.TelnyxLoggingTree
import com.telnyx.webrtc.sdk.utilities.encodeBase64
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*
import kotlin.concurrent.timerTask

/**
 * The TelnyxClient class that can be used to control the SDK. Create / Answer calls, change audio device, etc.
 *
 * @param context the Context that the application is using
 */
class TelnyxClient(
    var context: Context,
) : TxSocketListener {

    enum class RingtoneType {
        RAW,
        URI
    }

    /*
    * Add Later: Support current audio device i.e speaker or earpiece or bluetooth for incoming calls
    * */
    enum class SpeakerMode {
        SPEAKER,
        EARPIECE,
        UNASSIGNED
    }

    companion object {
        const val RETRY_REGISTER_TIME = 3
        const val RETRY_CONNECT_TIME = 3
        const val GATEWAY_RESPONSE_DELAY: Long = 3000

    }

    private var credentialSessionConfig: CredentialConfig? = null
    private var tokenSessionConfig: TokenConfig? = null

    private var reconnecting = false

    // Gateway registration variables
    private var autoReconnectLogin: Boolean = true
    private var gatewayResponseTimer: Timer? = null
    private var waitingForReg = true
    private var registrationRetryCounter = 0
    private var connectRetryCounter = 0
    private var gatewayState = "idle"
    private var speakerState: SpeakerMode = SpeakerMode.UNASSIGNED

    internal var socket: TxSocket
    private var providedHostAddress: String? = null
    private var providedPort: Int? = null
    internal var providedTurn: String? = null
    internal var providedStun: String? = null

    internal var debugReportStarted = false

    // MediaPlayer for ringtone / ringbacktone
    private var mediaPlayer: MediaPlayer? = null

    var sessid: String // sessid used to recover calls when reconnecting
    lateinit var socketResponseLiveData : MutableLiveData<SocketResponse<ReceivedMessageBody>>
    val wsMessagesResponseLiveDate = MutableLiveData<JsonObject>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as? AudioManager

    // Keeps track of all the created calls by theirs UUIDs
    internal val calls: MutableMap<UUID, Call> = mutableMapOf()

    @Deprecated("telnyxclient.call is deprecated. Use telnyxclient.[option] instead. e.g telnyxclient.newInvite()")
    val call: Call? by lazy {
        if (calls.isNotEmpty()) {
            val allCalls = calls.values
            val activeCall = allCalls.firstOrNull { it.getCallState().value == CallState.ACTIVE }
            activeCall ?: allCalls.first() // return the first
        } else {
            buildCall()
        }
    }


    private var isCallPendingFromPush: Boolean = false
    private var pushMetaData: PushMetaData? = null
    private fun processCallFromPush(metaData: PushMetaData) {
        Log.d("processCallFromPush PushMetaData", metaData.toJson())
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


    /* Accepts an incoming call
    * Local user response with both local and remote SDPs
    * @param callId, the callId provided with the invitation
    * @param destinationNumber, the number or SIP name that will receive the invitation
    * @see [Call]
    */
    fun acceptCall(
        callId: UUID,
        destinationNumber: String,
        customHeaders: Map<String, String>? = null
    ) : Call {
        val acceptCall = calls[callId]
        acceptCall!!.apply {
            val uuid: String = UUID.randomUUID().toString()
            val sessionDescriptionString =
                peerConnection?.getLocalDescription()?.description
            if (sessionDescriptionString == null) {
                callStateLiveData.postValue(CallState.ERROR)
            }
            else {
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
                // reset audio mode to communication
                speakerState?.let { setSpeakerMode(it) }


                callStateLiveData.postValue(CallState.ACTIVE)
                client.callOngoing()

            }
        }
        this.addToCalls(acceptCall)
        return acceptCall
    }

    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null
    ) : Call {
        val inviteCall = call!!.copy(
            context = context,
            client = this,
            socket = socket,
            sessionId = sessid,
            audioManager = audioManager!!,
            providedTurn = providedTurn!!,
            providedStun = providedStun!!
        ).apply {
            val uuid: String = UUID.randomUUID().toString()
            val inviteCallId: UUID = UUID.randomUUID()

            callId = inviteCallId

            // Create new peer
            peerConnection = Peer(
                context, client, providedTurn, providedStun,callId.toString(),
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
                                customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf()
                            )
                        )
                    )
                    socket.send(inviteMessageBody)
                },
                Call.ICE_CANDIDATE_DELAY
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
            val byeResponse = ByeResponse(callId)
            // send bye message to the UI
            client.socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.BYE.methodName,
                        byeResponse
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
            answerResponse = null
            inviteResponse = null
        }
    }

    /**
     * Add specified call to the calls MutableMap
     * @param call, and instance of [Call]
     */
    internal fun addToCalls(call: Call) {
        calls.getOrPut(call.callId) { call }
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
            Timber.d("[%s] :: There is a network available", this@TelnyxClient.javaClass.simpleName)
            // User has been logged in
            if (reconnecting && credentialSessionConfig != null || tokenSessionConfig != null) {
                runBlocking { reconnectToSocket() }
                reconnecting = false
            }
        }

        override fun onNetworkUnavailable() {
            Timber.d(
                "[%s] :: There is no network available",
                this@TelnyxClient.javaClass.simpleName
            )
            reconnecting = true
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }

    /**
     * Reconnect to the Telnyx socket using saved Telnyx Config - either Token or Credential based
     * @see [TxSocket]
     * @see [TelnyxConfig]
     */
    private suspend fun reconnectToSocket() = withContext(Dispatchers.Default) {
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
        if (!BuildConfig.IS_TESTING.get()) {
            Bugsnag.start(context)
        }

        // Generate random UUID for sessid param, convert it to string and set globally
        sessid = UUID.randomUUID().toString()

        socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())
        socket = TxSocket(
            host_address = Config.TELNYX_PROD_HOST_ADDRESS,
            port = Config.TELNYX_PORT
        )
        //socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()
        registerNetworkCallback()
    }

    private var rawRingtone: Any? = null
    private var rawRingbackTone: Int? = null

    /**
     * Return the saved ringtone reference
     * @returns [Int]
     */
    fun getRawRingtone(): Any? {
        return rawRingtone
    }

    /**
     * Return the saved ringback tone reference
     * @returns [Int]
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
     * required fot push calls to work
     *
     */
    @Deprecated("this telnyxclient.connect is deprecated. Use telnyxclient.connect(providedServerConfig,txPushMetaData,credential or tokenLogin) instead.")
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        txPushMetaData: String? = null,
    ) {

        socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())
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
            Timber.d("Provided Host Address: $providedHostAddress")
            socket.connect(this, providedHostAddress, providedPort, pushMetaData) {

            }
        } else {
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }


    /**
     * Connects to the socket using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required fot push calls to work
     *
     * @param autoLogin, if true, the SDK will automatically log in with the provided credentials on connection established
     * We recommend setting this to true
     *
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        credentialConfig: CredentialConfig,
        txPushMetaData: String?  = null,
        autoLogin: Boolean = true,
    ) {

        socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())
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
            Timber.d("Provided Host Address: $providedHostAddress")
            socket.connect(this, providedHostAddress, providedPort, pushMetaData) {
                if(autoLogin){
                    credentialLogin(credentialConfig)
                }
            }
        } else {
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }

    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    ) {

        socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())
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
            Timber.d("Provided Host Address: $providedHostAddress")
            socket.connect(this, providedHostAddress, providedPort, pushMetaData) {
                if(autoLogin){
                    tokenLogin(tokenConfig)
                }
            }
        } else {
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
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
     * Returns the socket response in the form of LiveData
     * The format of each message is provided in SocketResponse and ReceivedMessageBody
     * @see [SocketResponse]
     * @see [ReceivedMessageBody]
     */
    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData

    /**
     * Returns the  json messages from socket in the form of LiveData used for debugging purposes
     */
    fun getWsMessageResponse(): LiveData<JsonObject> = wsMessagesResponseLiveDate

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
        autoReconnectLogin = config.autoReconnect

        Config.USERNAME = config.sipUser
        Config.PASSWORD = config.sipPassword

        credentialSessionConfig = config

        setSDKLogLevel(logLevel)

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
        Timber.d("Auto login with credentialConfig")

        socket.send(loginMessage)
    }


    /**
     * Disables push notifications for current user
     *
     *  Takes :
     *  @param sipUserName : sip username of the current user or
     *  @param loginToken : fcm token of the device
     *  @param fcmToken : fcm token of the device
     * NB : Push Notifications are enabled by default after login
     *
     * returns : {"jsonrpc":"2.0","id":"","result":{"message":"disable push notification success"}}
     * */
    fun disablePushNotification(sipUserName: String?, loginToken: String?, fcmToken: String) {

        sipUserName ?: loginToken ?: return

        val params = when {
            sipUserName == null -> {
                TokenDisablePushParams(
                    loginToken = loginToken!!,
                    userVariables = UserVariables(fcmToken)
                )
            }

            loginToken == null -> {
                DisablePushParams(
                    user = sipUserName,
                    userVariables = UserVariables(fcmToken)
                )
            }

            else -> {
                return
            }
        }

        val disablePushMessage = SendingMessageBody(
            id = UUID.randomUUID().toString(),
            method = SocketMethod.DISABLE_PUSH.methodName,
            params = params
        )
        val message = Gson().toJson(disablePushMessage)
        Log.d("disablePushMessage", message)
        socket.send(disablePushMessage)
    }


    private fun attachCall() {

        val params = AttachCallParams(
            userVariables = AttachUserVariables()
        )

        val attachPushMessage = SendingMessageBody(
            id = UUID.randomUUID().toString(),
            method = SocketMethod.ATTACH_CALL.methodName,
            params = params
        )
        Log.d("sending attach Call", attachPushMessage.toString())
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
    @Deprecated("telnyxclient.tokenLogin is deprecated. Use telnyxclient.connect(...,autoLogin:true) with autoLogin set to true instead.")
    fun tokenLogin(config: TokenConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        autoReconnectLogin = config.autoReconnect

        tokenSessionConfig = config

        setSDKLogLevel(logLevel)

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

    internal  fun startStats(sessionId:UUID) {
        debugReportStarted = true
        val loginMessage = InitiateOrStopStatPrams(
            type = "debug_report_start",
            debugReportId = sessionId.toString(),
        )
        socket.send(loginMessage)
    }

    /**
     * Sends Logged webrtc stats to backend
     *
     * @param config, the TokenConfig used to log in
     * @see [TokenConfig]
     */
    internal fun sendStats(data:JsonObject,sessionId:UUID) {

        val loginMessage = StatPrams(
            debugReportId = sessionId.toString(),
            reportData = data
        )
        socket.send(loginMessage)

    }

    internal fun stopStats(sessionId:UUID) {
        debugReportStarted = false
        val loginMessage = InitiateOrStopStatPrams(
            debugReportId = sessionId.toString(),
        )
        socket.send(loginMessage)
    }

    /**
     * Sets the global SDK log level
     * Logging is implemented with Timber
     *
     * @param logLevel, the LogLevel specified for the SDK
     * @see [LogLevel]
     */
    private fun setSDKLogLevel(logLevel: LogLevel) {
        Timber.uprootAll()
        Timber.plant(TelnyxLoggingTree(logLevel))
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
                    Timber.d(
                        "[%s] :: No Bluetooth device detected",
                        this@TelnyxClient.javaClass.simpleName,
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
        speakerState = if (speakerState != SpeakerMode.UNASSIGNED) {
            if (audioManager?.isSpeakerphoneOn == true) {
                SpeakerMode.SPEAKER
            } else {
                SpeakerMode.EARPIECE
            }
        }else{
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
                    Timber.d("Ringtone not valid:: No ringtone will be played")
                    return
                }
                mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                if (mediaPlayer?.isPlaying == false) {
                    mediaPlayer!!.start()
                    mediaPlayer!!.isLooping = true
                }
                Timber.d("Ringtone playing")
            } catch (e: TypeCastException) {
                Timber.e("Exception: ${e.message}")
            }
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
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
            SpeakerMode.UNASSIGNED ->   audioManager?.isSpeakerphoneOn = false
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
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    /**
     * Stops any audio that the MediaPlayer is playing
     * @see [MediaPlayer]
     */
    internal fun stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        Timber.d("ringtone/ringback media player stopped and released")

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
        Timber.d(
            "[%s] :: onLoginSuccessful :: [%s] :: Ready to make calls",
            this@TelnyxClient.javaClass.simpleName,
            receivedLoginSessionId
        )
        sessid = receivedLoginSessionId
        socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.LOGIN.methodName,
                    LoginResponse(receivedLoginSessionId)
                )
            )
        )

        socket.isLoggedIn = true

        Timber.d("isCallPendingFromPush $isCallPendingFromPush")
        //if there is a call pending from push, attach it
        if (isCallPendingFromPush) {
            attachCall()
        }

        CoroutineScope(Dispatchers.Main).launch {
            socketResponseLiveData.postValue(
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
        if (gatewayState != GatewayState.REGED.state) {
            Timber.d(
                "[%s] :: onClientReady :: retrieving gateway state",
                this@TelnyxClient.javaClass.simpleName,
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
                            Timber.d(
                                "[%s] :: Gateway registration has timed out",
                                this@TelnyxClient.javaClass.simpleName,
                            )
                            socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has timed out"))
                        }
                    },
                    GATEWAY_RESPONSE_DELAY
                )
            }
        } else {
            Timber.d(
                "[%s] :: onClientReady :: Ready to make calls",
                this@TelnyxClient.javaClass.simpleName,
            )

            socketResponseLiveData.postValue(
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
                socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has timed out"))
            }

            GatewayState.FAILED.state -> {
                invalidateGatewayResponseTimer()
                socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has failed"))
            }

            GatewayState.FAIL_WAIT.state -> {
                if (autoReconnectLogin && connectRetryCounter < RETRY_CONNECT_TIME) {
                    connectRetryCounter++
                    Timber.d(
                        "[%s] :: Attempting reconnection :: attempt $connectRetryCounter / $RETRY_CONNECT_TIME",
                        this@TelnyxClient.javaClass.simpleName
                    )
                    runBlocking { reconnectToSocket() }
                } else {
                    invalidateGatewayResponseTimer()
                    socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has received fail wait response"))
                }
            }

            GatewayState.EXPIRED.state -> {
                invalidateGatewayResponseTimer()
                socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has timed out"))
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
                socketResponseLiveData.postValue(SocketResponse.error("Gateway registration has failed with an unknown error"))
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

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())

    }

    override fun onErrorReceived(jsonObject: JsonObject) {
        val errorMessage = jsonObject.get("error").asJsonObject.get("message").asString
        Timber.d("[%s] :: onErrorReceived ", errorMessage)
        socketResponseLiveData.postValue(SocketResponse.error(errorMessage))
    }

    override fun onByeReceived(callId: UUID) {

        Timber.d("[%s] :: onByeReceived", this.javaClass.simpleName)
        val byeCall = calls[callId]
        byeCall?.apply {
            Timber.d("[%s] :: onByeReceived", this.javaClass.simpleName)
            val byeResponse = ByeResponse(
                callId
            )
            client.socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.BYE.methodName,
                        byeResponse
                    )
                )
            )

            callStateLiveData.postValue(CallState.DONE)
            client.removeFromCalls(callId)
            client.callNotOngoing()
            resetCallOptions()
            client.stopMediaPlayer()
            peerConnection?.release()
            byeCall.endCall(callId)
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

                    callStateLiveData.postValue(CallState.ACTIVE)

                    val answerResponse = AnswerResponse(
                        UUID.fromString(callId),
                        stringSdp,
                        customHeaders?.toCustomHeaders() ?: arrayListOf()
                    )
                    this.answerResponse = answerResponse
                    client.socketResponseLiveData.postValue(
                        SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                SocketMethod.ANSWER.methodName,
                                answerResponse
                            )
                        )
                    )
                }

                earlySDP -> {
                    callStateLiveData.postValue(CallState.CONNECTING)
                    val stringSdp = peerConnection?.getLocalDescription()?.description
                    val answerResponse = AnswerResponse(
                        UUID.fromString(callId),
                        stringSdp!!,
                        customHeaders?.toCustomHeaders() ?: arrayListOf()
                    )
                    this.answerResponse = answerResponse
                    client.socketResponseLiveData.postValue(
                        SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                SocketMethod.ANSWER.methodName,
                                answerResponse
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

                val callerIDName = if (params.has("caller_id_name")) params.get("caller_id_name").asString else ""
                val callerNumber = if (params.has("caller_id_number")) params.get("caller_id_number").asString else ""

                val mediaResponse = MediaResponse(
                    UUID.fromString(callId),
                    callerIDName,
                    callerNumber,
                    sessionId,
                )
                client.socketResponseLiveData.postValue(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.MEDIA.methodName,
                            mediaResponse
                        )
                    )
                )

            } else {
                // There was no SDP in the response, there was an error.
                callStateLiveData.postValue(CallState.DONE)
                client.removeFromCalls(UUID.fromString(callId))
            }

        }


        /*Stop local Media and play ringback from telnyx cloud*/
        stopMediaPlayer()
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        if (jsonObject.has("params")) {
            Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
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
                val callerName = params.get("caller_id_name").asString
                val callerNumber = params.get("caller_id_number").asString
                telnyxSessionId = UUID.fromString(params.get("telnyx_session_id").asString)
                telnyxLegId = UUID.fromString(params.get("telnyx_leg_id").asString)

                // Set global callID
                callId = offerCallId

                //retrieve custom headers
                val customHeaders =
                    params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray
                peerConnection = Peer(
                    context, client, providedTurn, providedStun,offerCallId.toString(),
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
            addToCalls(offerCall)
            offerCall.client.socketResponseLiveData.postValue(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.INVITE.methodName,
                        offerCall.inviteResponse
                    )
                )
            )
            offerCall.client.playRingtone()
        } else {
            Timber.d(
                "[%s] :: Invalid offer received, missing required parameters [%s]",
                this.javaClass.simpleName, jsonObject
            )
        }

    }

    override fun onRingingReceived(jsonObject: JsonObject) {
        Timber.d(
            "[%s] :: onRingingReceived [%s]",
            this@TelnyxClient.javaClass.simpleName,
            jsonObject
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
            client.socketResponseLiveData.postValue(
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
            callStateLiveData.postValue(CallState.CONNECTING)
        }
    }

    override fun onDisablePushReceived(jsonObject: JsonObject) {
        Timber.d(
            "[%s] :: onDisablePushReceived [%s]",
            this@TelnyxClient.javaClass.simpleName,
            jsonObject
        )
        val errorMessage = jsonObject.get("result").asJsonObject.get("message").asString
        val disablePushResponse = DisablePushResponse(
            errorMessage.contains(DisablePushResponse.SUCCESS_KEY),
            errorMessage
        )
        socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.RINGING.methodName,
                    disablePushResponse
                )
            )
        )
    }

    override fun onAttachReceived(jsonObject: JsonObject) {
        val params = jsonObject.getAsJsonObject("params")
        val callId = UUID.fromString(params.get("callID").asString)
        val attachCall = calls[callId]
        attachCall?.apply {
            val remoteSdp = params.get("sdp").asString
            val callerNumber = params.get("caller_id_number").asString

            peerConnection = Peer(
                context, client, providedTurn, providedStun,callId.toString(),
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
                Call.ICE_CANDIDATE_DELAY
            )
        }
    }

    override fun setCallRecovering() {
        call?.setCallRecovering()
    }

    override fun pingPong() {
        Timber.d("[%s] :: pingPong ", this@TelnyxClient.javaClass.simpleName)
    }

    internal fun onRemoteSessionErrorReceived(errorMessage: String?) {
        stopMediaPlayer()
        socketResponseLiveData.postValue(errorMessage?.let { SocketResponse.error(it) })
    }

    /**
     * Disconnect from the TxSocket and unregister the provided network callback
     *
     * @see [ConnectivityHelper]
     * @see [TxSocket]
     */
    override fun onDisconnect() {
        socketResponseLiveData.postValue(SocketResponse.disconnect())
        invalidateGatewayResponseTimer()
        resetGatewayCounters()
        unregisterNetworkCallback()
        socket.destroy()
    }
}
