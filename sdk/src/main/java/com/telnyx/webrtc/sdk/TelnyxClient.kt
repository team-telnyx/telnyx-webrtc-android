package com.telnyx.webrtc.sdk

import android.content.Context
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.verto.send.LoginParam
import org.webrtc.IceCandidate
import timber.log.Timber
import java.util.*

class TelnyxClient(
    var socket: TxSocket,
    var context: Context
) : TxSocketListener {

    private var currentState: State = State.CLOSED
    private var clientListener: TelnyxClientListener? = null
    private var config: TelnyxConfig? = null
    private var sessionId: String? = null
    //private var call:Call? = null

    fun connect() {
        socket.connect(this)
    }

    fun login(config: TelnyxConfig){
        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = Method.LOGIN.methodName,
            params = LoginParam(user, password, arrayListOf(), arrayListOf())
        )

        socket.send(loginMessage)
    }

    enum class State {
        CONNECTED,
        CLOSED
    }

    override fun onLoginSuccessful(jsonObject: JsonObject) {
        Timber.d("[%s] :: onLoginSuccessful [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)
        val result = jsonObject.getAsJsonObject("result")
        val sessId = result.get("sessid").asString
        sessionId = sessId
        currentState = State.CONNECTED
    }

    override fun onByeReceived() {
        Timber.d("[%s] :: onByeReceived",this@TelnyxClient.javaClass.simpleName)
    }

    override fun onConnectionEstablished() {
        Timber.d("[%s] :: onConnectionEstablished",this@TelnyxClient.javaClass.simpleName)
    }
    override fun onOfferReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onOfferReceived [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)
    }
    override fun onAnswerReceived(jsonObject: JsonObject) {
        Timber.d("[%s] :: onAnswerReceived [%s]",this@TelnyxClient.javaClass.simpleName, jsonObject)
    }
    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        Timber.d("[%s] :: onIceCandidateReceived [%s]",this@TelnyxClient.javaClass.simpleName, iceCandidate)
    }
}