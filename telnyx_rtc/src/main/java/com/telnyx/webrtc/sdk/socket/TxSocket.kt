/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config
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
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.client.request.*
import io.ktor.http.cio.*
import io.ktor.http.cio.websocket.*
import okhttp3.*
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * The socket connection that will send and receive messages related to calls.
 * This class will trigger the TxSocketListener methods which can be observed to make use of the application
 *
 * @see TxSocketListener
 *
 * @param host_address the host address for the websocket to connect to
 * @param port the port that the websocket connection should use
 */
class TxSocket(
    internal var host_address: String,
    internal var port: Int
) : CoroutineScope {

    private var job: Job = SupervisorJob()
    private val gson = Gson()

    override var coroutineContext = Dispatchers.IO + job

    internal var ongoingCall = false
    internal var isLoggedIn = false
    internal var isConnected = false

    private lateinit var client: OkHttpClient
    private lateinit var socket: WebSocket

    /**
     * Connects to the socket with the provided Host Address and Port which were used to create an instance of TxSocket
     * @param listener, the [TelnyxClient] used to create an instance of TxSocket that contains our relevant listener methods via the [TxSocketListener] interface
     * @see [TxSocketListener]
     */
    fun connect(listener: TelnyxClient, providedHostAddress: String? = Config.TELNYX_PROD_HOST_ADDRESS, providedPort: Int? = Config.TELNYX_PORT) = launch {
        client = OkHttpClient.Builder()
            .addNetworkInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .hostnameVerifier ( hostnameVerifier = { _, _ -> true })
            .addInterceptor(Interceptor { chain ->
                val builder = chain.request().newBuilder()
                chain.proceed(builder.build())
            }).build()

        providedHostAddress?.let {
            host_address = it
        }
        providedPort?.let {
            port = it
        }

        val request: Request =
            Request.Builder().url("wss://$host_address:$port/").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.tag("VERTO").d("[%s] Connection established :: $host_address", this@TxSocket.javaClass.simpleName)
                listener.onConnectionEstablished()
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Timber.tag("VERTO").d(
                    "[%s] Receiving [%s]",
                    this@TxSocket.javaClass.simpleName,
                    text
                )
                val jsonObject = gson.fromJson(text, JsonObject::class.java)
                var params: JsonObject? = null
                if (jsonObject.has("params")) {
                     params = jsonObject.get("params").asJsonObject
                }
                when {
                        jsonObject.has("result") -> {
                            if (jsonObject.get("result").asJsonObject.has("params")) {
                                val result = jsonObject.get("result").asJsonObject
                                val sessionId = result.asJsonObject.get("sessid").asString
                                params = result.get("params").asJsonObject
                                if (params.asJsonObject.has("state")) {
                                    val gatewayState = params.get("state").asString
                                    listener.onGatewayStateReceived(gatewayState, sessionId)
                                }
                            }
                            else if (jsonObject.get("result").asJsonObject.has("message")) {
                                val result = jsonObject.get("result").asJsonObject
                                val message = result.get("message").asString
                                if (message == "logged in" && isLoggedIn) {
                                    listener.onClientReady(jsonObject)
                                }
                                else {
                                    listener.onSessionIdReceived(jsonObject)
                                }
                            }
                        }
                        params!==null && params.asJsonObject.has("state")  -> {
                             params = jsonObject.get("params").asJsonObject
                            if (params.asJsonObject.has("state")) {
                                val gatewayState = params.get("state").asString
                                listener.onGatewayStateReceived(gatewayState, null)
                            }
                        }
                        jsonObject.has("method") -> {
                            Timber.tag("VERTO").d(
                                "[%s] Received Method [%s]",
                                this@TxSocket.javaClass.simpleName,
                                jsonObject.get("method").asString
                            )
                            when (jsonObject.get("method").asString) {
                                CLIENT_READY.methodName -> {
                                    listener.onClientReady(jsonObject)
                                }
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
                                    val params =
                                        jsonObject.getAsJsonObject("params")
                                    val callId =
                                        UUID.fromString(params.get("callID").asString)
                                    listener.onByeReceived(callId)
                                }
                                INVITE.methodName -> {
                                    listener.onOfferReceived(jsonObject)
                                }
                                RINGING.methodName -> {
                                    listener.onRingingReceived(jsonObject)
                                }
                            }
                        }
                        jsonObject.has("error") -> {
                            if(jsonObject.get("error").asJsonObject.has("code")) {
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

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosing(webSocket, code, reason)
                Timber.tag("TxSocket").i("Socket is closing: $code :: $reason")

            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                super.onClosed(webSocket, code, reason)
                Timber.tag("TxSocket").i("Socket is closed: $code :: $reason")
                destroy()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.tag("TxSocket").i("Socket is closed: ${response.toString()} $t")
            }
        })
    }

    /**
     * Sets the ongoingCall boolean value to true
     */
    internal fun callOngoing() {
        ongoingCall = true
    }

    /**
     * Sets the ongoingCall boolean value to false
     */
    internal fun callNotOngoing() {
        ongoingCall = false
    }

    /**
     * Sends data to our open Telnyx Socket connection
     * @param dataObject, the data to be send to our subscriber
     */
    internal fun send(dataObject: Any?) = runBlocking {
        if(isConnected) {
            Timber.tag("VERTO")
                .d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, gson.toJson(dataObject))
            socket.send(gson.toJson(dataObject))
        } else {
            Timber.tag("VERTO").d("Message cannot be sent. There is no established WebSocket connection")
        }
    }

    /**
     * Closes our websocket connection and cancels our coroutine job
     */
    internal fun destroy() {
        isConnected = false
        isLoggedIn = false
        ongoingCall = false
        if (this::socket.isInitialized) {
            socket.cancel()
            //socket.close(1000, "Websocket connection was asked to close")
        }
        if (this::client.isInitialized) {
            launch(Dispatchers.IO) {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                client.cache?.close()
            }
        }
        job.cancel("Socket was destroyed, cancelling attached job")
    }
}