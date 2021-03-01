package com.telnyx.webrtc.sdk

import android.content.Context
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.verto.send.LoginParam
import java.util.*

class TelnyxClient(
    var socket: TxSocket,
    var context: Context
) /*: TxSocketListener*/ {

    private var currentState: State = State.CLOSED
    private var clientListener: TelnyxClientListener? = null
    private var config: TelnyxConfig? = null
    private var sessionId: String? = null
    //private var call:Call? = null

    fun connect() {
        socket.connect()
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

/*    override fun onLoginSuccessful(jsonObject: JsonObject) {
        TODO("Not yet implemented")
    }

    override fun onByeReceived() {
        TODO("Not yet implemented")
    }

    override fun onConnectionEstablished() {
        TODO("Not yet implemented")
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        TODO("Not yet implemented")
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        TODO("Not yet implemented")
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        TODO("Not yet implemented")
    } */
}