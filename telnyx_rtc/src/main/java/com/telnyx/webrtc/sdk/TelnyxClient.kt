/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.os.PowerManager
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.bugsnag.android.Bugsnag
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.TelnyxLoggingTree
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.*
import org.webrtc.IceCandidate
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

    internal var socket: TxSocket
    private var providedHostAddress: String? = null
    private var providedPort: Int? = null
    private var providedTurn: String? = null
    private var providedStun: String? = null

    // MediaPlayer for ringtone / ringbacktone
    private var mediaPlayer: MediaPlayer? = null

    var sessid: String // sessid used to recover calls when reconnecting
    val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()
    val wsMessagesResponseLiveDate = MutableLiveData<JsonObject>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as? AudioManager

    // Keeps track of all the created calls by theirs UUIDs
    internal val calls: MutableMap<UUID, Call> = mutableMapOf()

    val call: Call? by lazy { buildCall() }

    private var isCallPendingFromPush: Boolean = false
    private var rtcId: String? = null
    private fun processCallFromPush(metaData: PushMetaData) {
        isCallPendingFromPush = true
        this.rtcId = metaData.rtcId
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
                    providedStun!!
                )
            }
        } else {
            // We are testing, and will instead return a mocked call.
            return null
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
                    if (rtcId == null) Config.TELNYX_PROD_HOST_ADDRESS
                    else
                        Config.TELNYX_PROD_HOST_ADDRESS +
                                "?rtc_id=${rtcId}"
            }

            // Connect to new socket
            socket.connect(this@TelnyxClient, providedHostAddress, providedPort, rtcId)
            delay(1000)
            // Login with stored configuration
            credentialSessionConfig?.let {
                credentialLogin(it)
            } ?: tokenLogin(tokenSessionConfig!!)

            // Change an ongoing call's socket to the new socket.
            call?.let { call?.socket = socket }
        }
    }

    init {
        if (!BuildConfig.IS_TESTING.get()) {
            Bugsnag.start(context)
        }

        // Generate random UUID for sessid param, convert it to string and set globally
        sessid = UUID.randomUUID().toString()

        socket = TxSocket(
            host_address = Config.TELNYX_PROD_HOST_ADDRESS,
            port = Config.TELNYX_PORT
        )

        registerNetworkCallback()
    }

    private var rawRingtone: Int? = null
    private var rawRingbackTone: Int? = null

    /**
     * Return the saved ringtone reference
     * @returns [Int]
     */
    fun getRawRingtone(): Int? {
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
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        txPushMetaData: String?
    ) {

        if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
        }

        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        // if rtc id is not null, we are connecting to a call from push
        providedHostAddress =
            if (rtcId == null) providedServerConfig.host
            else providedServerConfig.host +
                    "?rtc_id=${rtcId}"

        Timber.d("Provided Host Address: $providedHostAddress")

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            socket.connect(this, providedHostAddress, providedPort, rtcId)
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
        rtcId = null
        isCallPendingFromPush = false

    }


    /**
     * Logs the user in with credentials provided via TokenConfig
     *
     * @param config, the TokenConfig used to log in
     * @see [TokenConfig]
     */
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
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        rawRingtone?.let {
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
     * Use MediaPlayer to play the audio of the saved user Ringback tone
     * If no ringback tone was provided, we print a relevant message
     *
     * @see [MediaPlayer]
     */
    internal fun playRingBackTone() {
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
        Timber.d("[%s] :: onByeReceived", this@TelnyxClient.javaClass.simpleName)
        call?.onByeReceived(callId)
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        call?.onAnswerReceived(jsonObject)
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        call?.onMediaReceived(jsonObject)
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
        call?.onOfferReceived(jsonObject)
    }

    override fun onRingingReceived(jsonObject: JsonObject) {
        Timber.d(
            "[%s] :: onRingingReceived [%s]",
            this@TelnyxClient.javaClass.simpleName,
            jsonObject
        )
        call?.onRingingReceived(jsonObject)
        socketResponseLiveData.postValue(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.RINGING.methodName,
                    null
                )
            )
        )
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        call?.onIceCandidateReceived(iceCandidate)
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
        call?.onAttachReceived(jsonObject)
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
