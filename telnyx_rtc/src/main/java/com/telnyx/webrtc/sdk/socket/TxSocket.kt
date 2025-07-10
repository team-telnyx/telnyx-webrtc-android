/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketError
import com.telnyx.webrtc.sdk.model.SocketMethod.*
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.logging.HttpLoggingInterceptor
import com.telnyx.webrtc.sdk.utilities.Logger
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
    private lateinit var webSocket: WebSocket
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
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
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
        Logger.d(message = "request: $client.")

        val request: Request =
            Request.Builder().url(requestUrl).build()


        Logger.d(message = "request2 : ${request.url.encodedQuery}")

        webSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Logger.v(
                        message = Logger.formatMessage("[%s] Connection established :: $host_address",
                        this@TxSocket.javaClass.simpleName)
                    )
                    isConnected = true
                    onConnected(true)
                    listener.onConnectionEstablished()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    super.onMessage(webSocket, text)
                    Logger.v(
                        message = Logger.formatMessage("[%s] Receiving [%s]",
                        this@TxSocket.javaClass.simpleName,
                        text)
                    )
                    val jsonObject = gson.fromJson(text, JsonObject::class.java)
                    listener.emitWsMessage(jsonObject)

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
                            Logger.v(
                                message = Logger.formatMessage("[%s] Received Method [%s]",
                                this@TxSocket.javaClass.simpleName,
                                jsonObject.get("method").asString)
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
                                    listener.onByeReceived(jsonObject)
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
                            val errorObject = jsonObject.get("error").asJsonObject
                            val errorCode = if (errorObject.has("code")) {
                                errorObject.get("code").asInt
                            } else null
                            
                            // Safely extract error message with fallback options
                            val errorMessage = when {
                                errorObject.has("message") -> {
                                    val messageElement = errorObject.get("message")
                                    if (messageElement.isJsonNull) "Unknown error" else messageElement.asString
                                }
                                errorObject.has("params") -> {
                                    val paramsElement = errorObject.get("params")
                                    if (paramsElement.isJsonObject && paramsElement.asJsonObject.has("state")) {
                                        "Error with state: ${paramsElement.asJsonObject.get("state").asString}"
                                    } else {
                                        "Error with params: ${paramsElement.toString()}"
                                    }
                                }
                                else -> "Unknown error: ${errorObject.toString()}"
                            }
                            
                            Logger.v(
                                message = Logger.formatMessage("[%s] Received Error From Telnyx [%s]%s",
                                this@TxSocket.javaClass.simpleName,
                                errorMessage,
                                if (errorCode != null) " with code [$errorCode]" else " (no code provided)")
                            )
                            listener.onErrorReceived(jsonObject, errorCode)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosing(webSocket, code, reason)
                    Logger.i(tag = "TxSocket", message = "Socket is closing: $code :: $reason")
                    listener.onDisconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    super.onClosed(webSocket, code, reason)
                    Logger.i(tag = "TxSocket", message = "Socket is closed: $code :: $reason")
                    destroy()
                    listener.onDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Logger.i(tag = "TxSocket",
                        message = "Socket failure: $t :: response: $response :: Will attempt to reconnect")

                    var errorCode: Int? = null
                    var errorMessage: String = t.message ?: "Unknown socket failure"

                    val unknownHostErrorCode = SocketError.GATEWAY_TIMEOUT_ERROR.errorCode
                    val socketTimeoutErrorCode = SocketError.GATEWAY_TIMEOUT_ERROR.errorCode
                    val genericNetworkErrorCode = SocketError.GATEWAY_FAILURE_ERROR.errorCode

                    when (t) {
                        is java.net.UnknownHostException -> {
                            errorMessage = "Unable to resolve host: ${t.message ?: host_address}"
                            errorCode = unknownHostErrorCode
                        }
                        is java.net.SocketTimeoutException -> {
                            errorMessage = "Socket connection timeout: ${t.message}"
                            errorCode = socketTimeoutErrorCode
                        }
                        is java.io.IOException -> {
                            // Catch other IOExceptions that might occur during connection attempts
                            errorMessage = "Network I/O error: ${t.message}"
                            errorCode = genericNetworkErrorCode
                        }
                    }

                    // Construct a JsonObject to pass to onErrorReceived
                    val errorPayload = JsonObject().apply {
                        addProperty("message", errorMessage)
                        errorCode?.let { addProperty("code", it) }
                    }
                    val fullJson = JsonObject().apply {
                        add("error", errorPayload)
                        addProperty("jsonrpc", "2.0")
                        // Add a unique ID as it's often expected by the receiver logic
                        addProperty("id", UUID.randomUUID().toString())
                    }
                    listener.onErrorReceived(fullJson, errorCode)

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
        Logger.v(
            message = Logger.formatMessage("[%s] Sending [%s]", 
            this@TxSocket.javaClass.simpleName, gson.toJson(dataObject))
        )
        webSocket.send(gson.toJson(dataObject))
    }

    /**
     * Closes our websocket connection and cancels our coroutine job
     */
    internal fun destroy() {
        isConnected = false
        isLoggedIn = false
        ongoingCall = false
        if (this::webSocket.isInitialized) {
            webSocket.cancel()
            // socket.close(1000, "Websocket connection was asked to close")
        }
        job.cancel("Socket was destroyed, cancelling attached job")
    }

    companion object {
        const val STATE_ATTACHED = "ATTACHED"
    }
}
