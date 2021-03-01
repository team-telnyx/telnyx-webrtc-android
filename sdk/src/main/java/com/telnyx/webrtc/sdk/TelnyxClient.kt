package com.telnyx.webrtc.sdk

import android.content.Context
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.telnyx.webrtc.library.socket.PlatformSocketListener
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.verto.send.LoginParam
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.webrtc.IceCandidate
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