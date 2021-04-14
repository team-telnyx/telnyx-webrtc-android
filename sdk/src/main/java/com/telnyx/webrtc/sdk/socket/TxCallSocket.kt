package com.telnyx.webrtc.sdk.socket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.model.SocketMethod.*
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.server.cio.backend.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import timber.log.Timber
import java.util.*

class TxCallSocket(
    var webSocketSession: DefaultWebSocketSession
) : CoroutineScope {

    private var job = Job()
    private val gson = Gson()

    override val coroutineContext = Dispatchers.IO + job

    private val callSendChannel = ConflatedBroadcastChannel<String>()

    fun callListen(callListener: Call) = launch {
        Timber.tag("VERTO").d("Connection established")
        val callSend = callSendChannel.openSubscription()
        try {
            while (true) {
                /*webSocketSession.outgoing.invokeOnClose {
                    val message = it?.message
                    Timber.tag("VERTO").d("The outgoing call channel was closed $message")
                }*/
                callSend.poll()?.let {
                    Timber.tag("VERTO").d("[%s] Call Listener Sending [%s]", this@TxCallSocket.javaClass.simpleName, it)
                    if (!webSocketSession.outgoing.isClosedForSend) {
                        webSocketSession.outgoing.send(Frame.Text(it))

                    } else {
                        Timber.tag("VERTO").d("[%s] Call Listener Channel Closed Because: [%s]", this@TxCallSocket.javaClass.simpleName, webSocketSession.closeReason)
                    }
                }
                //No longer receive for connect socket, then reopen and poll for call socket
                webSocketSession.incoming.poll()?.let { frame ->
                    if (frame is Frame.Text) {
                        val data = frame.readText()
                        Timber.tag("VERTO").d("[%s] Call Listener Receiving [%s]", this@TxCallSocket.javaClass.simpleName, data)
                        val jsonObject = gson.fromJson(data, JsonObject::class.java)
                        withContext(Dispatchers.Main) {
                            when {
                                jsonObject.has("method") -> {
                                    Timber.tag("VERTO").d("[%s] Call Listener Received Method [%s]", this@TxCallSocket.javaClass.simpleName, jsonObject.get("method").asString)
                                    when (jsonObject.get("method").asString) {
                                        ANSWER.methodName -> {
                                            callListener.onAnswerReceived(jsonObject)
                                        }
                                        MEDIA.methodName -> {
                                            callListener.onMediaReceived(jsonObject)
                                        }
                                        BYE.methodName -> {
                                            val params = jsonObject.getAsJsonObject("params")
                                            val callId = UUID.fromString(params.get("callID").asString)
                                            callListener.onByeReceived(callId)
                                        }
                                        INVITE.methodName -> {
                                            callListener.onOfferReceived(jsonObject)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (exception: Throwable) {
            Timber.e( exception)
        }
    }

    internal fun reconnectCall(call: Call) {
        job = Job()
        callListen(call)
    }

    internal fun callSend(dataObject: Any?) = runBlocking {
        callSendChannel.send(gson.toJson(dataObject))
    }

    internal fun destroy() {
        //callSendChannel.close()
        job.cancel()
    }
}