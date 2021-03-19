package com.telnyx.webrtc.sdk.socket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.Method.*
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.http.*
import io.ktor.http.cio.websocket.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import timber.log.Timber

@ExperimentalCoroutinesApi
@KtorExperimentalAPI
class TxCallSocket(
    var webSocketSession: DefaultWebSocketSession
) : CoroutineScope {

    private val job = Job()
    private val gson = Gson()

    override val coroutineContext = Dispatchers.IO + job

    private val callSendChannel = ConflatedBroadcastChannel<String>()

    fun callListen(callListener: Call) = launch {
        Timber.d("Connection established")
        val callSend = callSendChannel.openSubscription()
        try {
            while (true) {
                callSend.poll()?.let {
                    Timber.d("[%s] Call Listener Sending [%s]", this@TxCallSocket.javaClass.simpleName, it)
                   webSocketSession.outgoing.send(Frame.Text(it))
                }
                //No longer receive for connect socket, then reopen and poll for call socket
                webSocketSession.incoming.poll()?.let { frame ->
                    if (frame is Frame.Text) {
                        val data = frame.readText()
                        Timber.d("[%s] Call Listener Receiving [%s]", this@TxCallSocket.javaClass.simpleName, data)
                        val jsonObject = gson.fromJson(data, JsonObject::class.java)
                        withContext(Dispatchers.Main) {
                            when {
                                jsonObject.has("method") -> {
                                    Timber.d("[%s] Call Listener Received Method [%s]", this@TxCallSocket.javaClass.simpleName, jsonObject.get("method").asString)
                                    when (jsonObject.get("method").asString) {
                                        ANSWER.methodName -> {
                                            callListener.onAnswerReceived(jsonObject)
                                        }
                                        MEDIA.methodName -> {
                                            callListener.onMediaReceived(jsonObject)
                                        }
                                        BYE.methodName -> {
                                            callListener.onByeReceived()
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

    fun callSend(dataObject: Any?) = runBlocking {
        callSendChannel.send(gson.toJson(dataObject))
    }

    fun destroy() {
        callSendChannel.close()
        job.cancel()
    }
}