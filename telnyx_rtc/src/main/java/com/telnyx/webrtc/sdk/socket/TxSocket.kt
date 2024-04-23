/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketError.CREDENTIAL_ERROR
import com.telnyx.webrtc.sdk.model.SocketError.TOKEN_ERROR
import com.telnyx.webrtc.sdk.model.SocketMethod.*
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
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
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override var coroutineContext = Dispatchers.IO + job

    internal var ongoingCall = false
    internal var isLoggedIn = false
    internal var isConnected = false
    internal var isPing = false

    private lateinit var client: OkHttpClient
    private lateinit var socket: WebSocket
    /**
     * Connects to the socket with the provided Host Address and Port which were used to create an instance of TxSocket
     * @param listener the [TelnyxClient] used to create an instance of TxSocket that contains our
     * relevant listener methods via the [TxSocketListener] interface
     * @param providedHostAddress the host address specified when connecting,
     * will default to Telnyx Production Host if not specified.
     * @param providedPort the port specified when connecting,
     * will use default Telnyx Port if not specified.
     * @see [TxSocketListener]
     */
    fun connect(
        listener: TelnyxClient,
        providedHostAddress: String? = Config.TELNYX_PROD_HOST_ADDRESS,
        providedPort: Int? = Config.TELNYX_PORT,
        pushmetaData: PushMetaData? = null,
        onConnected:(Boolean) -> Unit = {}
    ) = launch {

        val loggingInterceptor = HttpLoggingInterceptor()
        loggingInterceptor.apply {
            if (BuildConfig.DEBUG){
                loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
            }else {
                loggingInterceptor.level = HttpLoggingInterceptor.Level.NONE
            }
        }

        client = OkHttpClient.Builder()
            .addNetworkInterceptor(HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BODY))
            .retryOnConnectionFailure(true)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .hostnameVerifier(hostnameVerifier = { _, _ -> true })
            .addInterceptor(
                Interceptor { chain ->
                    val builder = chain.request().newBuilder()
                    chain.proceed(builder.build())
                }
            ).addInterceptor(
                loggingInterceptor
            ).build()



        providedHostAddress?.let {
            host_address = it
        }
        providedPort?.let {
            port = it
        }

        val requestUrl = if (pushmetaData != null) {
            HttpUrl.Builder()
                .scheme("https")
                .host(host_address)
                .addQueryParameter("voice_sdk_id", pushmetaData.voiceSdkId ?: "")
                .build()
        } else {
            HttpUrl.Builder()
                .scheme("https")
                .port(port)
                .host(host_address)
                .build()
        }
        Timber.d("request: $client.")

        val request: Request =
            Request.Builder().url(requestUrl).build()


        Timber.d("request2 : ${request.url.encodedQuery}")

        socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Timber.tag("VERTO").d(
                        "[%s] Connection established :: $host_address",
                        this@TxSocket.javaClass.simpleName
                    )
                    onConnected(true)
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
                    listener.wsMessagesResponseLiveDate.postValue(jsonObject)

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
                                    if (gatewayState != STATE_ATTACHED) {
                                        listener.onGatewayStateReceived(gatewayState, sessionId)
                                    }
                                }
                            } else if (jsonObject.get("result").asJsonObject.has("message")) {
                                val result = jsonObject.get("result").asJsonObject
                                val message = result.get("message").asString
                                if (message == "logged in" && isLoggedIn) {
                                    listener.onClientReady(jsonObject)
                                }
                            }
                        }

                        params !== null && params.asJsonObject.has("state") -> {
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

                                ATTACH.methodName -> {
                                    listener.onAttachReceived(jsonObject)
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

                                DISABLE_PUSH.methodName -> {
                                    listener.onDisablePushReceived(jsonObject)
                                }
                                PINGPONG.methodName -> {
                                    isPing = true
                                    webSocket.send(text)
                                    listener.pingPong()
                                }
                            }
                        }

                        jsonObject.has("error") -> {
                            if (jsonObject.get("error").asJsonObject.has("code")) {
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
                    listener.onDisconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    Timber.tag("TxSocket").i("Socket is closed: $code :: $reason")
                    destroy()
                    listener.onDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    t.printStackTrace()
                    Timber.tag("TxSocket")
                        .i("Socket is closed: ${response?.message} $t :: Will attempt to reconnect")
                    if (ongoingCall) {
                        listener.call?.setCallRecovering()
                    }
                }
            }
        )
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
        if (isConnected) {
            Timber.tag("VERTO")
                .d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, gson.toJson(dataObject))
            socket.send(gson.toJson(dataObject))
        } else {
            Timber.tag("VERTO")
                .d("Message cannot be sent. There is no established WebSocket connection")
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
            // socket.close(1000, "Websocket connection was asked to close")
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

    companion object {
        const val STATE_ATTACHED = "ATTACHED"
    }
}
