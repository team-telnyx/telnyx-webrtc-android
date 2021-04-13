package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.sdk.BuildConfig
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.TelnyxLoggingTree
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import timber.log.Timber
import java.util.*

class TelnyxClient(
    var context: Context,
    var socket: TxSocket,
) : TxSocketListener {

    //MediaPlayer for ringtone / ringbacktone
    private var mediaPlayer: MediaPlayer? = null

    private var sessionId: String? = null
    val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as? AudioManager

    /// Keeps track of all the created calls by theirs UUIDs
    private val calls: MutableMap<UUID, Call> = mutableMapOf()

    val call: Call?  by lazy { buildCall() }

    private fun buildCall(): Call {
        val txCallSocket = TxCallSocket(socket.getWebSocketSession())
        return Call(
            context,
            this, txCallSocket, sessionId!!, audioManager!!
        )
    }

    internal fun addToCalls(call: Call) {
        println("Incoming callID to add: ${call.callId}")
        calls.getOrPut(call.callId) { call }
    }

    internal fun removeFromCalls(callId: UUID) {
        println("Incoming callID to remove: $callId")
        calls.entries.forEach {
            println("callID in stack: " + it.key)
        }
        calls.remove(callId)
    }

    internal var isNetworkCallbackRegistered = false
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

    private var rawRingtone: Int? = null
    private var rawRingbackTone: Int? = null


    fun getRawRingtone(): Int? {
        return rawRingtone
    }

    fun getRawRingbackTone(): Int? {
        return rawRingbackTone
    }

    fun connect() {
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            socket.connect(this)
        } else {
            socketResponseLiveData.postValue(SocketResponse.error("No Network Connection"))
        }
    }

    internal fun callOngoing() {
        socket.callOngoing()
    }

    internal fun callNotOngoing() {
        if (calls.isEmpty()) {
            socket.callNotOngoing()
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
    fun getActiveCalls(): Map<UUID, Call> {
        return calls.toMap()
    }

    fun credentialLogin(config: CredentialConfig) {
        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword
        val logLevel = config.logLevel
        setSDKLogLevel(logLevel)


        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
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
        val logLevel = config.logLevel
        setSDKLogLevel(logLevel)

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
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

    private fun setSDKLogLevel(logLevel: LogLevel) {
        Timber.uprootAll()
        if (BuildConfig.DEBUG) {
            Timber.plant(TelnyxLoggingTree(logLevel))
        }
    }

    private fun getAvailableAudioOutputTypes(): MutableList<Int> {
        val availableTypes: MutableList<Int> = mutableListOf()
        audioManager!!.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            availableTypes.add(it.type)
        }
        return availableTypes
    }

    fun setAudioOutputDevice(audioDevice: AudioDevice) {
        val availableTypes = getAvailableAudioOutputTypes()
        when (audioDevice) {
            AudioDevice.BLUETOOTH -> {
                if (availableTypes.contains(AudioDevice.BLUETOOTH.code)) {
                    audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION;
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                } else {
                    Timber.d(
                        "[%s] :: No Bluetooth device detected",
                        this@TelnyxClient.javaClass.simpleName,
                    )
                }
            }
            AudioDevice.PHONE_EARPIECE -> {
                //For phone ear piece
                audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION;
                audioManager.stopBluetoothSco();
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            AudioDevice.LOUDSPEAKER -> {
                //For phone speaker(loudspeaker)
                audioManager!!.mode = AudioManager.MODE_NORMAL;
                audioManager.stopBluetoothSco();
                audioManager.isBluetoothScoOn = false;
                audioManager.isSpeakerphoneOn = true;
            }
        }
    }

    internal fun playRingtone() {
        rawRingtone?.let {
            stopMediaPlayer()
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            if (!mediaPlayer!!.isPlaying) {
                mediaPlayer!!.start()
                mediaPlayer!!.isLooping = true
            }
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    internal fun playRingBackTone() {
        rawRingbackTone?.let {
            stopMediaPlayer()
            mediaPlayer = MediaPlayer.create(context, it)
            mediaPlayer!!.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            if (!mediaPlayer!!.isPlaying) {
                mediaPlayer!!.start()
                mediaPlayer!!.isLooping = true
            }
        } ?: run {
            Timber.d("No ringtone specified :: No ringtone will be played")
        }
    }

    internal fun stopMediaPlayer() {
        if (mediaPlayer != null && mediaPlayer!!.isPlaying) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            //mediaPlayer = null
        }
        Timber.d("ringtone/ringback media player stopped and released")
    }


    // TxSocketListener Overrides
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
                    SocketMethod.LOGIN.methodName,
                    LoginResponse(sessionId!!)
                )
            )
        )
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())
    }

    override fun onErrorReceived(jsonObject: JsonObject) {
        val errorMessage = jsonObject.get("error").asJsonObject.get("message").asString
        socketResponseLiveData.postValue(SocketResponse.error(errorMessage))
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
        call?.onOfferReceived(jsonObject)
    }

    internal fun onRemoteSessionErrorReceived(errorMessage: String?) {
        stopMediaPlayer()
        socketResponseLiveData.postValue(errorMessage?.let { SocketResponse.error(it) })
    }

    fun disconnect() {
        unregisterNetworkCallback()
        socket.destroy()
    }
}