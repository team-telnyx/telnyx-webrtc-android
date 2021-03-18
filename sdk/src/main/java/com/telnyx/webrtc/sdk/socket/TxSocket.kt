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
class TxSocket(
        private val host_address: String,
        private val port: Int
) : CoroutineScope {

    private val job = Job()
    private val gson = Gson()

    override val coroutineContext = Dispatchers.IO + job

    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private lateinit var webSocketSession: DefaultClientWebSocketSession

    private val sendChannel = ConflatedBroadcastChannel<String>()
    private val callSendChannel = ConflatedBroadcastChannel<String>()

    fun connect(listener: TelnyxClient) = launch {
        try { client.wss(
                    host = host_address,
                    port = port
            ) {
                webSocketSession = this
                listener.onConnectionEstablished()
                Timber.d("Connection established")
                val sendData = sendChannel.openSubscription()
                try {
                    while (true) {
                        sendData.poll()?.let {
                            Timber.d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, it)
                            outgoing.send(Frame.Text(it))
                        }
                        incoming.poll()?.let { frame ->
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                Timber.d("[%s] Receiving [%s]", this@TxSocket.javaClass.simpleName, data)
                                val jsonObject = gson.fromJson(data, JsonObject::class.java)
                                withContext(Dispatchers.Main) {
                                    when {
                                        jsonObject.has("result") -> {
                                            if (jsonObject.get("result").asJsonObject.has("message")) {
                                                val result = jsonObject.get("result")
                                                val message = result.asJsonObject.get("message").asString
                                                if (message == "logged in") {
                                                    listener.onLoginSuccessful(jsonObject)
                                                }
                                            }
                                        }
                                        jsonObject.has("method") -> {
                                            Timber.d("[%s] Received Method [%s]", this@TxSocket.javaClass.simpleName, jsonObject.get("method").asString)
                                            when (jsonObject.get("method").asString) {
                                                INVITE.methodName -> {
                                                    listener.onOfferReceived(jsonObject)
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
        } catch (cause: Throwable) {
            Timber.d("Check Network Connection :: $cause")
        }
    }
        //ToDo can we move this to call? The whole listener?
    fun callListen(listener: Call) = launch {
                Timber.d("Connection established")
                val callSend = callSendChannel.openSubscription()
                try {
                    while (true) {
                        callSend.poll()?.let {
                            Timber.d("[%s] Call Listener Sending [%s]", this@TxSocket.javaClass.simpleName, it)
                            webSocketSession.outgoing.send(Frame.Text(it))
                        }
                        webSocketSession.incoming.poll()?.let { frame ->
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                Timber.d("[%s] Call Listener Receiving [%s]", this@TxSocket.javaClass.simpleName, data)
                                val jsonObject = gson.fromJson(data, JsonObject::class.java)
                                withContext(Dispatchers.Main) {
                                    when {
                                        jsonObject.has("method") -> {
                                            Timber.d("[%s] Call Listener Received Method [%s]", this@TxSocket.javaClass.simpleName, jsonObject.get("method").asString)
                                            when (jsonObject.get("method").asString) {
                                                ANSWER.methodName -> {
                                                    listener.onAnswerReceived(jsonObject)
                                                }
                                                MEDIA.methodName -> {
                                                    listener.onMediaReceived(jsonObject)
                                                }
                                                BYE.methodName -> {
                                                    listener.onByeReceived()
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

    fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    fun callSend(dataObject: Any?) = runBlocking {
        callSendChannel.send(gson.toJson(dataObject))
    }

    fun destroy() {
        client.close()
        job.cancel()
    }
}