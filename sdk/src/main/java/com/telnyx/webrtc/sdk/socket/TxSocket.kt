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
import io.ktor.http.cio.*
import io.ktor.http.cio.websocket.*
import timber.log.Timber
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit

class TxSocket(
    private val host_address: String,
    private val port: Int
) : CoroutineScope {

    private var job = Job()
    private val gson = Gson()

    internal var ongoingCall = false

    override val coroutineContext = Dispatchers.IO + job

    /*private val client = HttpClient(OkHttp) {
        install(WebSockets) {
            pingInterval = 100000 // Disabled (null) by default
        }
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        ConnectionOptions.KeepAlive
        install(HttpTimeout) {
            // timeout config
            requestTimeoutMillis = 100000
            socketTimeoutMillis = 100000
            connectTimeoutMillis = 10000
        }
        engine {
            config {
                connectTimeout(30, TimeUnit.SECONDS)
                readTimeout(60, TimeUnit.SECONDS)
                writeTimeout(60, TimeUnit.SECONDS)
                pingInterval(1000, TimeUnit.SECONDS)
            }
        }
    }*/

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 50000
            endpoint.connectTimeout = 100000
            endpoint.connectAttempts = 30
            endpoint.keepAliveTime = 100000
        }
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
        ConnectionOptions.KeepAlive
        expectSuccess = false
        install(HttpTimeout) {
            // timeout config
            //requestTimeoutMillis = 100000
        }
    }

    private lateinit var webSocketSession: DefaultClientWebSocketSession

    private val sendChannel = ConflatedBroadcastChannel<String>()

    private lateinit var telnyxClient: TelnyxClient

    fun connect(listener: TelnyxClient) = launch {
        telnyxClient = listener
        try {
            client.wss(
                host = host_address,
                port = port
            ) {
                outgoing.invokeOnClose {
                    val message = it?.message
                    Timber.tag("VERTO").d("The outgoing channel was closed $message")
                    destroy()
                }
                webSocketSession = this
                listener.onConnectionEstablished()
                Timber.tag("VERTO").d("Connection established")
                val sendData = sendChannel.openSubscription()
                try {
                    while (true) {
                        sendData.poll()?.let {
                            Timber.tag("VERTO")
                                .d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, it)
                            outgoing.send(Frame.Text(it))
                        }
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
                                                ANSWER.methodName -> {
                                                    listener.onAnswerReceived(jsonObject)
                                                }
                                                MEDIA.methodName -> {
                                                    listener.onMediaReceived(jsonObject)
                                                }
                                                BYE.methodName -> {
                                                    val params = jsonObject.getAsJsonObject("params")
                                                    val callId = UUID.fromString(params.get("callID").asString)
                                                    listener.onByeReceived(callId)
                                                }
                                                INVITE.methodName -> {
                                                    listener.onOfferReceived(jsonObject)
                                                }
                                            }
                                        }
                                        jsonObject.has("error") -> {
                                            val errorCode =
                                                jsonObject.get("error").asJsonObject.get("code").asInt
                                            Timber.tag("VERTO").d(
                                                "[%s] Received Error From Telnyx [%s]",
                                                this@TxSocket.javaClass.simpleName,
                                                jsonObject.get("error").asJsonObject.get("message")
                                                    .toString()
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
                } catch (exception: Throwable) {
                    Timber.e(exception)
                }
            }
        } catch (cause: Throwable) {
            Timber.d(cause)
        }
    }

    internal fun reconnect() {
        job = Job()
        connect(listener = telnyxClient)
    }

    internal fun callOngoing() {
        ongoingCall = true
    }

    internal fun callNotOngoing() {
        ongoingCall = false
    }

    internal fun getWebSocketSession(): DefaultClientWebSocketSession {
        return webSocketSession
    }

    internal fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    internal fun destroy() {
        client.close()
        job.cancel()
    }
}