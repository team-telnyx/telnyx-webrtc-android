package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.TelnyxLoggingTree
import com.telnyx.webrtc.sdk.utilities.encodeBase64
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
    var context: Context,
    var socket: TxSocket,
) : TxSocketListener {

    private var peerConnection: Peer? = null
    private var sessionId: String? = null
    val socketResponseLiveData = MutableLiveData<SocketResponse<ReceivedMessageBody>>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as AudioManager

    /// Keeps track of all the created calls by theirs UUIDs
    private val calls: MutableMap<UUID, Call> = mutableMapOf()

    lateinit var call: Call

    private fun buildCall(callId: UUID): Call {
        val txCallSocket = TxCallSocket(socket.getWebSocketSession())
        return Call(this, peerConnection, txCallSocket, callId, sessionId!!, audioManager, context)
    }

    private fun addToCalls(call: Call) {
        calls[call.callId] = call
    }

    internal fun removeFromCalls(call: Call) {
        calls.remove(call.callId)
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

    fun newInvite(callerName: String, callerNumber: String, destinationNumber: String, clientState: String) {
        val uuid: String = UUID.randomUUID().toString()
        val callId: UUID = UUID.randomUUID()
        var sentFlag = false

        //Create new peer
        peerConnection = Peer(this, context,
            object : PeerConnectionObserver() {
                override fun onIceCandidate(p0: IceCandidate?) {
                    super.onIceCandidate(p0)
                    peerConnection?.addIceCandidate(p0)

                    //set localInfo and ice candidate and able to create correct offer
                    val inviteMessageBody = SendingMessageBody(
                        id = uuid,
                        method = SocketMethod.INVITE.methodName,
                        params = CallParams(
                            sessionId = sessionId!!,
                            sdp = peerConnection?.getLocalDescription()?.description.toString(),
                            dialogParams = CallDialogParams(
                                callerIdName = callerName,
                                callerIdNumber = callerNumber,
                                clientState = clientState.encodeBase64(),
                                callId = callId,
                                destinationNumber = destinationNumber,
                            )
                        )
                    )

                    if (!sentFlag) {
                        sentFlag = true
                        socket.send(inviteMessageBody)
                    }
                }
            })
        callOngoing()
        peerConnection?.startLocalAudioCapture()
        peerConnection?.createOfferForSdp(AppSdpObserver())

        //Either do this here or on Answer received
        call = buildCall(callId)
        call.playRingBackTone()
        addToCalls(call)
    }

    fun disconnect() {
        peerConnection?.disconnect()
        unregisterNetworkCallback()
        socket.destroy()
    }

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

    override fun onOfferReceived(jsonObject: JsonObject) {
        /* In case of receiving an invite
          local user should create an answer with both local and remote information :
          1. create a connection peer
          2. setup ice candidate, local description and remote description
          3. connection is ready to be used for answer the call
          */

        val params = jsonObject.getAsJsonObject("params")
        val callId = UUID.fromString(params.get("callID").asString)
        val remoteSdp = params.get("sdp").asString
        val callerName = params.get("caller_id_name").asString
        val callerNumber = params.get("caller_id_number").asString

        peerConnection = Peer(this,
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
                    SocketMethod.INVITE.methodName,
                    InviteResponse(callId, remoteSdp, callerName, callerNumber, sessionId!!)
                )
            )
        )
        call = buildCall(callId)
        call.playRingtone()
        addToCalls(call)
    }

    override fun onErrorReceived(jsonObject: JsonObject) {
        val errorMessage = jsonObject.get("error").asJsonObject.get("message").asString
        socketResponseLiveData.postValue(SocketResponse.error(errorMessage))
    }
}