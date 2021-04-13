package com.telnyx.webrtc.sdk.socket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.SocketError.*
import com.telnyx.webrtc.sdk.model.SocketMethod.*
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.http.cio.websocket.*
import timber.log.Timber

class TxSocket(
    private val host_address: String,
    private val port: Int
) : CoroutineScope {

    private val job = Job()
    private val gson = Gson()

    internal var ongoingCall = false

    override val coroutineContext = Dispatchers.IO + job

    /*private val client = HttpClient(OkHttp) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        install(HttpTimeout) {
            // timeout config
            requestTimeoutMillis = 100000
        }
    }*/


    private val client = HttpClient(CIO) {
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        expectSuccess = false
        install(HttpTimeout) {
            // timeout config
            requestTimeoutMillis = 100000
        }
    }

    private lateinit var webSocketSession: DefaultClientWebSocketSession

    private val sendChannel = ConflatedBroadcastChannel<String>()

    fun connect(listener: TelnyxClient) = launch {
        try {
            client.wss(
                host = host_address,
                port = port
            ) {
                webSocketSession = this
                listener.onConnectionEstablished()
                Timber.tag("VERTO").d("Connection established")
                val sendData = sendChannel.openSubscription()
                try {
                    while (true) {
                        sendData.poll()?.let {
                            Timber.tag("VERTO").d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, it)
                            outgoing.send(Frame.Text(it))
                        }
                        while (!ongoingCall) {
                            incoming.poll()?.let { frame ->
                                if (frame is Frame.Text) {
                                    val data = frame.readText()
                                    Timber.tag("VERTO").d(
                                        "[%s] Receiving [%s]",
                                        this@TxSocket.javaClass.simpleName,
                                        data
                                    )
                                    val jsonObject = gson.fromJson(data, JsonObject::class.java)
                                    withContext(Dispatchers.Main) {
                                        when {
                                            jsonObject.has("result") -> {
                                                if (jsonObject.get("result").asJsonObject.has("message")) {
                                                    val result = jsonObject.get("result")
                                                    val message =
                                                        result.asJsonObject.get("message").asString
                                                    if (message == "logged in") {
                                                        listener.onLoginSuccessful(jsonObject)
                                                    }
                                                }
                                            }
                                            jsonObject.has("method") -> {
                                                Timber.tag("VERTO").d(
                                                    "[%s] Received Method [%s]",
                                                    this@TxSocket.javaClass.simpleName,
                                                    jsonObject.get("method").asString
                                                )
                                                when (jsonObject.get("method").asString) {
                                                    INVITE.methodName -> {
                                                        listener.onOfferReceived(jsonObject)
                                                    }
                                                }
                                            }
                                            jsonObject.has("error") -> {
                                                val errorCode = jsonObject.get("error").asJsonObject.get("code").asInt
                                                Timber.tag("VERTO").d(
                                                    "[%s] Received Error From Telnyx [%s]",
                                                    this@TxSocket.javaClass.simpleName,
                                                    jsonObject.get("error").asJsonObject.get("message").toString()
                                                )
                                                when (errorCode) {
                                                    CREDENTIAL_ERROR.errorCode -> {
                                                        listener.onErrorReceived(jsonObject)
                                                    }
                                                    TOKEN_ERROR.errorCode -> {
                                                        listener.onErrorReceived(jsonObject)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (exception: Throwable) {
                    Timber.e(exception)
                }
            }
        } catch (cause: Throwable) {
            Timber.d(cause)
        }
    }

    internal fun callOngoing() {
        ongoingCall = true
    }

    internal fun callNotOngoing() {
        ongoingCall = false
    }

    fun getWebSocketSession(): DefaultClientWebSocketSession {
        return webSocketSession
    }

    fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    fun destroy() {
        client.close()
        job.cancel()
    }
}