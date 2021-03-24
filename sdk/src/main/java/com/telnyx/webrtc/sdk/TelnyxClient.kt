package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import io.ktor.util.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*

@KtorExperimentalAPI
@ExperimentalCoroutinesApi
class TelnyxClient(
    var socket: TxSocket,
    var context: Context
) : TxSocketListener {

    private var peerConnection: Peer? = null
    private var sessionId: String? = null
    val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as AudioManager

    val call: Call? by lazy { buildCall() }

    private fun buildCall(): Call {
        val txCallSocket = TxCallSocket(socket.getWebSocketSession())
        return Call(this, txCallSocket, sessionId!!, audioManager, context)
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

    fun getSessionID(): String? {
        return sessionId
    }

    internal fun callOngoing() {
        socket.callOngoing()
    }

    internal fun callNotOngoing() {
        socket.callNotOngoing()
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
            rawRingbackTone = it
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


    /* Sanity check --

        1. You use the client to get a get request to see all available audio devices - preferably by name

        2. You then do a call to change the audio device to - passing the name (or other attribute)

   */

    private fun getAvailableAudioOutputTypes(): MutableList<Int> {
        val availableTypes: MutableList<Int> = mutableListOf()
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach {
            availableTypes.add(it.type)
        }
        return availableTypes
    }

    fun setAudioOutputDevice(audioDevice: AudioDevice) {
        val availableTypes = getAvailableAudioOutputTypes()
        when (audioDevice) {
            AudioDevice.BLUETOOTH -> {
                if (availableTypes.contains(AudioDevice.BLUETOOTH.code)) {
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION;
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
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION;
                audioManager.stopBluetoothSco();
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            AudioDevice.LOUDSPEAKER -> {
                //For phone speaker(loudspeaker)
                audioManager.mode = AudioManager.MODE_NORMAL;
                audioManager.stopBluetoothSco();
                audioManager.isBluetoothScoOn = false;
                audioManager.isSpeakerphoneOn = true;
            }
        }
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
                    Method.LOGIN.methodName,
                    LoginResponse(sessionId!!)
                )
            )
        )
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished", this@TelnyxClient.javaClass.simpleName)
        socketResponseLiveData.postValue(SocketResponse.established())
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]", this@TelnyxClient.javaClass.simpleName, jsonObject)
        call?.onOfferReceived(jsonObject)
    }
}