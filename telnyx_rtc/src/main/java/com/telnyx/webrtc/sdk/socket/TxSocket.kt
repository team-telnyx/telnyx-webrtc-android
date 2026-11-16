/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
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
import kotlin.concurrent.timerTask
import kotlin.math.sqrt


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

    private var reportingClient: TelnyxClient? = null
    private val v2Logger = com.telnyx.webrtc.sdk.observability.V2ScopedLogger { reportingClient?.callReportV2 }
    private val gatewayChecks = com.telnyx.webrtc.sdk.observability.V2GatewayCheckTracker(
        started = { id -> reportingClient?.callReportV2?.gatewayCheckStarted(id) },
        result = { id, state -> reportingClient?.callReportV2?.gatewayCheckResult(id, state) },
        failed = { id, message, willRetry -> reportingClient?.callReportV2?.gatewayCheckFailed(id, message, willRetry) }
    )
    private var job: Job = SupervisorJob()
    private val gson = GsonBuilder().setPrettyPrinting().create()

    override var coroutineContext = Dispatchers.IO + job

    internal var ongoingCall = false
    internal var isLoggedIn = false
    internal var isConnected = false
    internal var isPing = false
    @Volatile
    private var isDestroyed = false

    private lateinit var client: OkHttpClient
    private lateinit var webSocket: WebSocket
    
    // Connection metrics tracking
    private val pingTimestamps = mutableListOf<Long>()
    private val pingIntervals = mutableListOf<Long>()
    private var lastPingTimestamp: Long? = null
    private var connectionStartTime: Long? = null
    private var expectedPingTimer: Timer? = null
    private var missedPingCount = 0
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
    ): Job = connectWithConnectionGuard(
        listener,
        providedHostAddress,
        providedPort,
        pushmetaData,
        isCurrentConnection = { true },
        onConnected = onConnected
    )

    internal fun connectWithConnectionGuard(
        listener: TelnyxClient,
        providedHostAddress: String? = Config.TELNYX_PROD_HOST_ADDRESS,
        providedPort: Int? = Config.TELNYX_PORT,
        pushmetaData: PushMetaData? = null,
        isCurrentConnection: () -> Boolean = { true },
        onConnected:(Boolean) -> Unit = {}
    ): Job {
        isDestroyed = false
        return launch(Dispatchers.IO) {
        reportingClient = listener
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
        v2Logger.d(message = "request: $client.")

        // t027: region and resume voice_sdk_id describe the connection target the SDK
        // is about to open (already applied region prefix / push recovery id).
        listener.callReportV2?.socketStarting(requestUrl.toString(),
            region = listener.v2RegionHint,
            resumeVoiceSdkId = listener.v2ResumeVoiceSdkId())

        val request: Request =
            Request.Builder().url(requestUrl).build()


        v2Logger.d(message = "request2 : ${request.url.encodedQuery}")

        if (shouldIgnoreSocketCallback(isCurrentConnection)) return@launch

        val createdWebSocket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (shouldIgnoreSocketCallback(isCurrentConnection)) return
                    // Guard against duplicate onOpen callbacks — if already connected,
                    // don't fire onConnectionEstablished again (prevents duplicate login).
                    if (isConnected) {
                        v2Logger.w(
                            message = v2Logger.formatMessage(
                                "[%s] onOpen fired but already connected — ignoring duplicate",
                                this@TxSocket.javaClass.simpleName
                            )
                        )
                        return
                    }

                    v2Logger.v(
                        message = v2Logger.formatMessage("[%s] Connection established :: $host_address",
                        this@TxSocket.javaClass.simpleName)
                    )
                    // t027: the final URL is the one OkHttp actually opened (response request URL).
                    listener.callReportV2?.socketOpened(requestUrl.toString(),
                        finalUrl = response.request.url.toString(),
                        region = listener.v2RegionHint,
                        resumeVoiceSdkId = listener.v2ResumeVoiceSdkId())
                    isConnected = true
                    onConnected(true)
                    listener.onConnectionEstablished()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (shouldIgnoreSocketCallback(isCurrentConnection)) return

                    listener.callReportV2?.signalingFrame(text, outbound = false)
                    super.onMessage(webSocket, text)
                    v2Logger.v(
                        message = v2Logger.formatMessage("[%s] Receiving [%s]",
                        this@TxSocket.javaClass.simpleName,
                        text)
                    )
                    val jsonObject = try {
                        gson.fromJson(text, JsonObject::class.java)
                    } catch (e: com.google.gson.JsonSyntaxException) {
                        // The server sent a non-JSON-object payload (e.g. a JSON primitive,
                        // stale heartbeat, or error string). Log the raw bytes so we can
                        // diagnose the issue, then emit an error instead of crashing the
                        // WebSocket callback.
                        val previewLength = PAYLOAD_PREVIEW_LENGTH
                        val preview = if (text.length > previewLength) text.substring(0, previewLength) + "..." else text
                        v2Logger.e(
                            tag = "TxSocket",
                            message = "Failed to parse WebSocket message as JsonObject: $preview"
                        )
                        // Construct an error JsonObject so the listener can handle it
                        val errorPayload = JsonObject().apply {
                            addProperty("message", "Invalid WebSocket payload: $preview")
                        }
                        val fullJson = JsonObject().apply {
                            add("error", errorPayload)
                            addProperty("jsonrpc", "2.0")
                            addProperty("id", UUID.randomUUID().toString())
                        }
                        listener.onErrorReceived(fullJson, null)
                        return
                    }
                    gatewayChecks.received(jsonObject, listener.socketFailureWillRetry())
                    listener.emitWsMessage(jsonObject)

                    var params: JsonObject? = null
                    if (jsonObject.has("params")) {
                        params = jsonObject.get("params").asJsonObject
                    }

                    when {
                        jsonObject.has("result") -> {
                            val result = jsonObject.get("result").asJsonObject
                            
                            // Check if this is a modify response (updateMedia result)
                            if (result.has("action") && result.get("action").asString == "updateMedia") {
                                listener.onModifyReceived(jsonObject)
                            } else if (result.has("params")) {
                                val sessionId = result.asJsonObject.get("sessid").asString
                                val params = result.get("params").asJsonObject
                                if (params.asJsonObject.has("state")) {
                                    val gatewayState = params.get("state").asString

                                    // Extract call_report_id if present in REGED response
                                    if (gatewayState == STATE_REGED && params.has("call_report_id")) {
                                        val callReportId = params.get("call_report_id").asString
                                        listener.onCallReportIdReceived(callReportId)
                                    }

                                    if (gatewayState != STATE_ATTACHED) {
                                        // t027 B-GW: the gateway RPC response is correlated
                                        // against the CURRENT outstanding gateway RPC
                                        // (id + socket + auth epoch + generation) through
                                        // an internal Native-client dispatch seam so a
                                        // stale REGED can never complete a newer auth's
                                        // registration. The public TxSocketListener
                                        // surface is unchanged.
                                        listener.onGatewayStateResponseConfirmed(this@TxSocket,
                                            jsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
                                            gatewayState, sessionId)
                                    }
                                }
                            } else if (result.has("message")) {
                                // t027: EVERY Native login result is routed through
                                // ownership confirmation regardless of prior isLoggedIn — a
                                // same-socket re-login on an already-logged-in socket publishes
                                // a new auth but never clears isLoggedIn, so its success must
                                // bind the renewed session via the correlated RPC (and never
                                // be treated as auth proof for readiness). Readiness comes
                                // from actual CLIENT_READY pushes / correlated REGED facts.
                                if (result.get("message").asString == "logged in") {
                                    listener.onLoginResponseConfirmed(this@TxSocket,
                                        jsonObject.get("id")?.takeIf { it.isJsonPrimitive }?.asString,
                                        jsonObject)
                                }
                            }
                        }

                        params !== null && params.asJsonObject.has("state") -> {
                            params = jsonObject.get("params").asJsonObject
                            if (params.asJsonObject.has("state")) {
                                val gatewayState = params.get("state").asString

                                // Extract call_report_id if present in REGED response
                                if (gatewayState == STATE_REGED && params.has("call_report_id")) {
                                    val callReportId = params.get("call_report_id").asString
                                    listener.onCallReportIdReceived(callReportId)
                                }

                                listener.onGatewayStateReceived(gatewayState, null)
                            }
                        }

                        jsonObject.has("method") -> {
                            v2Logger.v(
                                message = v2Logger.formatMessage("[%s] Received Method [%s]",
                                this@TxSocket.javaClass.simpleName,
                                jsonObject.get("method").asString)
                            )
                            when (jsonObject.get("method").asString) {
                                CLIENT_READY.methodName -> {
                                    cleanPingIntervals()
                                    listener.onClientReady(jsonObject)
                                    connectionStartTime = System.currentTimeMillis()
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

                                MODIFY.methodName -> {
                                    listener.onModifyReceived(jsonObject)
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
                                AI_CONVERSATION.methodName -> {
                                    listener.onAiConversationReceived(jsonObject)
                                }
                                CANDIDATE.methodName -> {
                                    listener.onCandidateReceived(jsonObject)
                                }
                                END_OF_CANDIDATES.methodName -> {
                                    listener.onEndOfCandidatesReceived(jsonObject)
                                }
                                PINGPONG.methodName -> {
                                    isPing = true
                                    webSocket.send(text)
                                    handlePingReceived()
                                    val metrics = calculateConnectionMetrics()
                                    listener.pingPong(metrics)
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
                            
                            v2Logger.v(
                                message = v2Logger.formatMessage("[%s] Received Error From Telnyx [%s]%s",
                                this@TxSocket.javaClass.simpleName,
                                errorMessage,
                                if (errorCode != null) " with code [$errorCode]" else " (no code provided)")
                            )
                            listener.onErrorReceived(jsonObject, errorCode)
                        }
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (shouldIgnoreSocketCallback(isCurrentConnection)) return

                    super.onClosing(webSocket, code, reason)
                    v2Logger.i(tag = "TxSocket", message = "Socket is closing: $code :: $reason")
                    listener.onDisconnect()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (shouldIgnoreSocketCallback(isCurrentConnection)) return

                gatewayChecks.socketLost(false)
                // t027: will_reconnect reports only what the SDK knows. A reconnect
                // cycle in flight implies another attempt; otherwise the reconnect
                // decision has not been made yet and the field stays null.
                listener.callReportV2?.socketClosed(code, reason, failed = false,
                    willReconnect = if (listener.v2ReconnectingNow()) true else null)
                    super.onClosed(webSocket, code, reason)
                    v2Logger.i(tag = "TxSocket", message = "Socket is closed: $code :: $reason")
                    // Only cleanup if not already disconnected (prevents double cleanup)
                    if (isConnected) {
                        destroy()
                    }
                    listener.onDisconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (shouldIgnoreSocketCallback(isCurrentConnection)) return
                    val willRetry = listener.socketFailureWillRetry()
                    listener.callReportV2?.socketFailed(t.javaClass.simpleName, willRetry)
                    gatewayChecks.socketLost(willRetry)
                    // t027: will_reconnect reflects the actual retry decision — the
                    // socketFailureWillRetry() policy (which already respects the
                    // configured reconnect capability) or an in-flight reconnect
                    // cycle; never a guess from login state alone.
                    listener.callReportV2?.socketClosed(null, null, failed = true,
                        willReconnect = if (willRetry || listener.v2ReconnectingNow()) true else null)

                    v2Logger.i(tag = "TxSocket",
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

        webSocket = createdWebSocket
        if (shouldIgnoreSocketCallback(isCurrentConnection)) {
            createdWebSocket.close(WEBSOCKET_NORMAL_CLOSURE, "Websocket connection closed")
        }
        }
    }

    private fun shouldIgnoreSocketCallback(isCurrentConnection: () -> Boolean = { true }): Boolean {
        return isDestroyed || !job.isActive || !isCurrentConnection()
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
        if (isDestroyed) {
            v2Logger.w(
                message = v2Logger.formatMessage(
                    "[%s] Attempted to send on destroyed socket — ignoring",
                    this@TxSocket.javaClass.simpleName
                )
            )
            return@runBlocking
        }
        v2Logger.v(
            message = v2Logger.formatMessage("[%s] Sending [%s]",
            this@TxSocket.javaClass.simpleName, gson.toJson(dataObject))
        )
        val text = gson.toJson(dataObject)
        val capture = reportingClient?.callReportV2
        // Register correlation before send: another thread may deliver a response before send returns.
        capture?.signalingWriteStarted(text)
        val gatewayCheckId = gatewayChecks.beforeWrite(text)
        var accepted = false
        try { accepted = webSocket.send(text) } finally {
            if (!accepted) {
                capture?.signalingWriteRejected(text)
                gatewayChecks.rejected(gatewayCheckId)
            }
        }
        if (accepted) capture?.signalingFrame(text, outbound = true)
    }

    /**
     * Sends data to the socket and reports whether the frame was accepted for
     * delivery. Mirrors the JS SDK's `sendMessage` promise-rejection surface
     * (VSDK-393 parity): OkHttp's `WebSocket.send` returns `false` when the
     * connection is closed or the outgoing queue is full, which previously
     * failed silently.
     */
    internal fun sendReturning(dataObject: Any?): Boolean = runBlocking {
        if (isDestroyed) return@runBlocking false
        val text = gson.toJson(dataObject)
        val capture = reportingClient?.callReportV2
        capture?.signalingWriteStarted(text)
        val gatewayCheckId = gatewayChecks.beforeWrite(text)
        val accepted = try { webSocket.send(text) } catch (e: Exception) {
            v2Logger.e(message = "Socket send threw: ${e.message}")
            false
        }
        if (accepted) capture?.signalingFrame(text, outbound = true)
        else {
            capture?.signalingWriteRejected(text)
            gatewayChecks.rejected(gatewayCheckId)
        }
        accepted
    }

    /**
     * Closes our websocket connection and cancels our coroutine job
     */
    internal fun destroy() {
        gatewayChecks.socketLost(false)
        // t027: a client-initiated close is a decided outcome — no reconnect is
        // scheduled by this close unless a reconnect cycle is already in flight.
        reportingClient?.callReportV2?.socketClosed(WEBSOCKET_NORMAL_CLOSURE, "client_disconnect", failed = false, byClient = true,
            willReconnect = reportingClient?.v2ReconnectingNow() == true)
        isDestroyed = true
        isConnected = false
        isLoggedIn = false
        ongoingCall = false
        
        cleanPingIntervals()

        if (this::webSocket.isInitialized) {
            webSocket.close(WEBSOCKET_NORMAL_CLOSURE, "Websocket connection closed")
        }
        job.cancel("Socket was destroyed, cancelling attached job")
    }

    /**
     * Handles a received ping from the server and tracks timing information
     */
    private fun handlePingReceived() {
        val currentTime = System.currentTimeMillis()
        
        // Calculate interval from last ping or connection start time
        val referenceTime = lastPingTimestamp ?: connectionStartTime
        referenceTime?.let { refTime ->
            val interval = currentTime - refTime
            pingIntervals.add(interval)
            
            // Keep only recent intervals
            while (pingIntervals.size > MAX_PING_HISTORY_SIZE_VALUE) {
                pingIntervals.removeAt(0)
            }
        }
        
        // Track timestamp
        lastPingTimestamp = currentTime
        pingTimestamps.add(currentTime)
        while (pingTimestamps.size > MAX_PING_HISTORY_SIZE_VALUE) {
            pingTimestamps.removeAt(0)
        }
        
        // Reset missed ping counter and restart timer
        resetExpectedPingTimer()
    }
    
    /**
     * Resets the timer that tracks expected pings
     */
    private fun resetExpectedPingTimer() {
        expectedPingTimer?.cancel()
        expectedPingTimer = Timer()
        expectedPingTimer?.schedule(
            timerTask {
                // If this fires, we missed an expected ping (with tolerance)
                missedPingCount++
                v2Logger.w(message = "Expected ping not received within ${EXPECTED_PING_INTERVAL_MS_VALUE + PING_TOLERANCE_MS_VALUE}ms")
            },
            EXPECTED_PING_INTERVAL_MS_VALUE + PING_TOLERANCE_MS_VALUE
        )
    }
    
    /**
     * Calculates current connection metrics based on ping history.
     * Returns CALCULATING quality when insufficient data is available,
     * then progressively provides better assessment as more pings are received.
     */
    private fun calculateConnectionMetrics(): SocketConnectionMetrics {
        if (pingIntervals.isEmpty()) {
            return SocketConnectionMetrics()
        }
        
        val currentInterval = pingIntervals.lastOrNull()
        val averageInterval = pingIntervals.average().toLong()
        val minInterval = pingIntervals.minOrNull()
        val maxInterval = pingIntervals.maxOrNull()
        
        // Calculate jitter (standard deviation)
        val jitter = if (pingIntervals.size > 1) {
            val variance = pingIntervals.map { interval ->
                val diff = interval - averageInterval
                diff * diff
            }.average()
            sqrt(variance).toLong()
        } else {
            null
        }
        
        // Calculate quality based on metrics
        val quality = SocketConnectionMetrics.calculateQuality(averageInterval, jitter)
        
        return SocketConnectionMetrics(
            intervalMs = currentInterval,
            averageIntervalMs = averageInterval,
            minIntervalMs = minInterval,
            maxIntervalMs = maxInterval,
            jitterMs = jitter,
            missedPings = missedPingCount,
            totalPings = pingTimestamps.size,
            quality = quality,
            timestamp = System.currentTimeMillis(),
            lastPingTimestamp = lastPingTimestamp
        )
    }

    private fun cleanPingIntervals() {
        // Reset connection metrics tracking
        connectionStartTime = null
        lastPingTimestamp = null
        pingTimestamps.clear()
        pingIntervals.clear()
        missedPingCount = 0
        expectedPingTimer?.cancel()
        expectedPingTimer = null
    }

    companion object {
        const val STATE_ATTACHED = "ATTACHED"
        const val STATE_REGED = "REGED"

        // WebSocket constants
        private const val WEBSOCKET_NORMAL_CLOSURE = 1000
        private const val PAYLOAD_PREVIEW_LENGTH = 500

        // Ping tracking constants
        private const val MAX_PING_HISTORY_SIZE_VALUE = 30
        private const val EXPECTED_PING_INTERVAL_MS_VALUE = 30000L
        private const val PING_TOLERANCE_MS_VALUE = 500L
    }
}
