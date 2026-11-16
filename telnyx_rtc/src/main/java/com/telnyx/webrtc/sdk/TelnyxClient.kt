/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.*
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient.RingtoneType.RAW
import com.telnyx.webrtc.sdk.TelnyxClient.RingtoneType.URI
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.EARPIECE
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.SPEAKER
import com.telnyx.webrtc.sdk.TelnyxClient.SpeakerMode.UNASSIGNED
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.model.AudioConstraints
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.socket.TxSocketListener
import com.telnyx.webrtc.sdk.stats.DebugDataCollector
import com.telnyx.webrtc.sdk.stats.WebRTCReporter
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.CallTimingBenchmark
import com.telnyx.webrtc.sdk.utilities.CandidateUtils
import com.telnyx.webrtc.sdk.utilities.JwtUtils
import com.telnyx.webrtc.sdk.utilities.LatencyTracker
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.TxLogger
import com.telnyx.webrtc.sdk.verto.receive.*
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.SessionDescription
import com.telnyx.webrtc.sdk.utilities.SdpUtils
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.timerTask

/**
 * The TelnyxClient class that can be used to control the SDK. Create / Answer calls, change audio device, etc.
 *
 * @param context the Context that the application is using
 */
@Suppress("LargeClass")
class TelnyxClient private constructor(
    var context: Context,
    startsInDeclinePushMode: Boolean,
    /**
     * Immutable SDK identity carried on every outbound verto message. Set once
     * at construction; no setter is exposed so consumer code cannot change the
     * identity mid-session.
     */
    val sdkIdentity: SdkIdentity,
) : TxSocketListener {
    constructor(context: Context) : this(context, false, SdkIdentity.default())
    constructor(context: Context, sdkIdentity: SdkIdentity) : this(context, false, sdkIdentity)

    private val v2Logger = com.telnyx.webrtc.sdk.observability.V2ScopedLogger { callReportV2 }
    /** Stable per-client identity; never an account ID or signaling session ID. */
    internal val callReportV2CreatedAtMs: Long = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime())
    internal var callReportV2CreationDurationMs: Long = 0
        private set
    internal val callReportV2InstanceId: String = UUID.randomUUID().toString()
    private val callReportV2Sequence = java.util.concurrent.atomic.AtomicLong()
    internal fun nextCallReportV2Sequence(): Long = callReportV2Sequence.incrementAndGet()
    internal val callReportV2DisabledByServer = java.util.concurrent.atomic.AtomicBoolean(false)
    private var callReportV2ServerSessionId: String? = null
    /** Set by the installer while holding the client lock; sticky for the client's lifetime. */
    @Volatile internal var callReportV2ExplicitlyConfigured = false
        internal set
    /** Null while no V2 owner is installed; otherwise the telemetry configuration in effect. */
    internal val callReportV2InstalledConfig: com.telnyx.webrtc.sdk.observability.CallReportV2Config?
        get() = com.telnyx.webrtc.sdk.observability.AndroidV2Installer.installedConfigFor(this)
    @Volatile internal var callReportV2: com.telnyx.webrtc.sdk.observability.CallReportV2Ingress? = null

    /** Configure reporting before login/call creation; identical settings preserve the owner. */
    fun configureCallReportV2(config: com.telnyx.webrtc.sdk.observability.CallReportV2Config): Boolean =
        try {
            com.telnyx.webrtc.sdk.observability.AndroidV2Installer.install(this, config, explicit = true)
            true
        } catch (_: Exception) { false } catch (_: LinkageError) { false }

    /**
     * Keeps telemetry on the signaling environment the app connected with unless the app
     * explicitly configured Call Report V2; a custom host is treated as production and no
     * prod-to-dev fallback exists. Check and install are serialized on the client lock so an
     * explicit configuration can never be overwritten by a concurrent auto-apply.
     */
    internal fun applyCallReportV2Environment(providedServerConfig: TxServerConfiguration) {
        synchronized(this) {
            if (callReportV2ExplicitlyConfigured) return
            if (callReportV2DisabledByServer.get()) return
            if (calls.isNotEmpty() || callReportV2?.hasActiveCall() == true) return
            val installed = com.telnyx.webrtc.sdk.observability.AndroidV2Installer.installedConfigFor(this) ?: return
            val derived = installed.copy(development = providedServerConfig.host == Config.TELNYX_DEV_HOST_ADDRESS)
            if (derived == installed) return
            runCatching { com.telnyx.webrtc.sdk.observability.AndroidV2Installer.install(this, derived) }
                .onFailure { failure ->
                    v2Logger.d(
                        message = v2Logger.formatMessage(
                            "Call Report V2 environment retarget not applied: %s", failure.message ?: failure::class.java.simpleName
                        )
                    )
                }
        }
    }

    internal var webRTCReportersMap: ConcurrentHashMap<UUID, WebRTCReporter> =
        ConcurrentHashMap<UUID, WebRTCReporter>()

    internal val debugDataCollector: DebugDataCollector = DebugDataCollector(context)
    
    /**
     * Latency tracker for measuring SDK performance metrics.
     * Use this to observe registration and call establishment latencies.
     */
    val latencyTracker: LatencyTracker = LatencyTracker()

    /**
     * Enum class that defines the type of ringtone resource.
     *
     * @property RAW The ringtone is a raw resource in the app
     * @property URI The ringtone is referenced by a URI
     */
    enum class RingtoneType {
        RAW,
        URI
    }

    /*
    * Add Later: Support current audio device i.e speaker or earpiece or bluetooth for incoming calls
    * */
    /**
     * Enum class that defines the current audio output mode.
     *
     * @property SPEAKER Audio output through the device's loudspeaker
     * @property EARPIECE Audio output through the device's earpiece
     * @property UNASSIGNED No specific audio output mode assigned
     */
    enum class SpeakerMode {
        SPEAKER,
        EARPIECE,
        UNASSIGNED
    }


    /**
     * Companion object containing constant values used throughout the client.
     */
    companion object {
        /**
         * Creates a client isolated for background decline-push operations.
         */
        fun createDeclinePushClient(context: Context): TelnyxClient =
            TelnyxClient(context, true, SdkIdentity.default())

        /** Number of times to retry registration */
        const val RETRY_REGISTER_TIME = 3

        /** Number of times to retry connection */
        const val RETRY_CONNECT_TIME = 3

        /** Delay in milliseconds before gateway response timeout */
        const val GATEWAY_RESPONSE_DELAY: Long = 3000

        /** Delay in milliseconds before attempting to reconnect */
        const val RECONNECT_DELAY: Long = 1000

        /** Minimum delay in ms before retrying after a WebSocket parse error */
        private const val RECONNECT_AFTER_PARSE_ERROR_MIN_MS: Long = 500

        /** JSON-RPC server error code for auth failures (Token_Error in the Verto protocol). */
        const val JSON_RPC_AUTH_ERROR = -32000

        /** Jitter range in ms added to [RECONNECT_AFTER_PARSE_ERROR_MIN_MS] for parse-error retries */
        private const val RECONNECT_AFTER_PARSE_ERROR_JITTER_MS: Long = 1500

        /** Timeout in milliseconds for reconnection attempts (60 seconds) */
        const val RECONNECT_TIMEOUT: Long = 60000
        internal const val PUSH_INVITE_TIMEOUT_MS: Long = 10000

        /** Maximum number of reconnection retries when server closes connection during reconnection */
        const val MAX_RECONNECTION_RETRIES = 3

        /** Base delay for exponential backoff during reconnection retries (milliseconds) */
        const val RECONNECTION_RETRY_BASE_DELAY: Long = 1000

        /** Timeout dividend*/
        const val TIMEOUT_DIVISOR: Long = 1000

        /** SDK version*/
        const val SDK_VERSION = BuildConfig.SDK_VERSION
    }

    private var credentialSessionConfig: CredentialConfig? = null
    private var tokenSessionConfig: TokenConfig? = null
    private var useTrickleIce: Boolean = false
    @Volatile
    private var reconnecting = false
    // Signaling call IDs whose recovery reattach must rebuild the peer with
    // relay-only ICE (a failed direct VPN path). Kept per call so another
    // call's attach can never consume the override.
    private val forceRelayRecoveredCallIds: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    // Counter for reconnection retries when server closes connection during reconnection
    private var reconnectionRetryCounter = AtomicInteger(0)
    private val connectionGeneration = AtomicInteger(0)

    // Owns TelnyxClient-level async work; TxSocket owns its socket connection scope.
    private val clientScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Reconnection timeout timer
    private var reconnectTimeOutJob: Job? = null
    // t027: monotonic owner epoch of the reconnection timer; an old job can only
    // decide/tear down/clear when it still owns the current job+epoch pair.
    private var v2TimerEpoch: Long = 0
    private var socketConnectJob: Job? = null
    private val acceptCallJobs = ConcurrentHashMap<UUID, Job>()
    private var reconnectJob: Job? = null

    // JS parity (VSDK-393): token expiry warning timer — fires TOKEN_EXPIRING_SOON
    // TOKEN_EXPIRY_WARNING_SECONDS before the login_token JWT expires.
    private var tokenExpiryJob: Job? = null

    // Gateway registration variables
    private var autoReconnectLogin: Boolean = true
    private var gatewayResponseTimer: Timer? = null
    private var waitingForReg = true
    private val isDedicatedDeclinePushClient = startsInDeclinePushMode
    @Volatile
    private var isDeclinePushConnection = startsInDeclinePushMode
    private var registrationRetryCounter = 0
    private var connectRetryCounter = 0
    private var gatewayState = "idle"
    private var speakerState: SpeakerMode = UNASSIGNED

    internal var socket: TxSocket
    private var providedHostAddress: String? = null
    private var providedPort: Int? = null
    internal var providedTurn: String? = null
    internal var providedStun: String? = null
    // t027: region applied to the current connection (null until a non-AUTO region is configured),
    // reported in sdk-creation and socket lifecycle events.
    internal var v2RegionHint: String? = null
    // t027: latest outstanding LOGIN publication (rpc id + owning socket + auth epoch),
    // guarded by the same monitor as the commit in onLoginResponseConfirmedImpl so the
    // response-to-session transition is linearizable.
    private var v2PendingLogin: PendingLogin? = null
    private var v2LoginAttemptEpoch: Long = 0
    private var voiceSDKID: String? = null
    private var callReportId: String? = null

    private var isSocketDebug = false
    private var enableCallQualityReports = false
    private var callQualityReportInterval: Long = 5000L
    private var sendWebRTCStatsViaSocket = false

    // MediaPlayer for ringtone / ringbacktone
    private var mediaPlayer: MediaPlayer? = null

    var sessid: String // sessid used to recover calls when reconnecting

    // SharedFlow for socket responses (replaces LiveData)
    private val _socketResponseFlow = MutableSharedFlow<SocketResponse<ReceivedMessageBody>>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the socket response in the form of SharedFlow (recommended)
     * The format of each message is provided in SocketResponse and ReceivedMessageBody
     * @see [SocketResponse]
     * @see [ReceivedMessageBody]
     */
    val socketResponseFlow: SharedFlow<SocketResponse<ReceivedMessageBody>> =
        _socketResponseFlow.asSharedFlow()

    // SharedFlow for structured errors (VSDK-393)
    private val _errorFlow = MutableSharedFlow<TelnyxError>(replay = 1, extraBufferCapacity = 64)
    private val _warningFlow = MutableSharedFlow<TelnyxWarning>(replay = 0, extraBufferCapacity = 64)

    /**
     * Returns structured errors in the form of SharedFlow.
     * Each [TelnyxError] carries a code, name, message, description, causes,
     * solutions, and a fatal flag aligned with the JS SDK error registry.
     * @see [TelnyxError]
     */
    val errorFlow: SharedFlow<TelnyxError> = _errorFlow.asSharedFlow()

    /**
     * Returns structured warnings in the form of SharedFlow.
     * Each [TelnyxWarning] is a non-fatal event indicating degraded quality
     * or a recoverable state.
     * @see [TelnyxWarning]
     */
    val warningFlow: SharedFlow<TelnyxWarning> = _warningFlow.asSharedFlow()

    // Deprecated LiveData - kept for backward compatibility
    @Deprecated("Use socketResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    var socketResponseLiveData: MutableLiveData<SocketResponse<ReceivedMessageBody>>

    // SharedFlow for ws messages responses (replaces LiveData)
    private val _wsMessagesResponseFlow = MutableSharedFlow<JsonObject>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the ws messages response in the form of SharedFlow (recommended)
     * The format of each message is provided in JsonObject
     */
    val wsMessagesResponseFlow: SharedFlow<JsonObject> = _wsMessagesResponseFlow.asSharedFlow()

    // SharedFlow for connection status
    private val _connectionStatusFlow = MutableSharedFlow<ConnectionStatus>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the connection status in the form of SharedFlow
     * Exposes the current connection state of the Telnyx client
     * @see [ConnectionStatus]
     */
    val connectionStatusFlow: SharedFlow<ConnectionStatus> = _connectionStatusFlow.asSharedFlow()

    /**
     * Helper function to emit connection status updates
     */
    private fun emitConnectionStatus(status: ConnectionStatus) {
        _connectionStatusFlow.tryEmit(status)
    }

    /**
     * Helper function to emit socket response to both SharedFlow and deprecated LiveData
     */
    fun emitWsMessage(message: JsonObject) {
        // Match iOS SignalingHealthMonitor: every inbound frame updates liveness,
        // but only the exact JSON-RPC id resolves a recovery probe.
        calls.values
            .mapNotNull { it.peerConnection }
            .toSet()
            .forEach { it.onSignalingMessage(message) }

        _wsMessagesResponseFlow.tryEmit(message)
        wsMessagesResponseLiveData.postValue(message)
    }

    // Deprecated LiveData - kept for backward compatibility
    @Deprecated("Use wsMessagesResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    val wsMessagesResponseLiveData = MutableLiveData<JsonObject>()

    private val audioManager =
        context.getSystemService(AppCompatActivity.AUDIO_SERVICE) as? AudioManager

    /**
     * Helper function to emit socket response to both SharedFlow and deprecated LiveData
     * for backward compatibility during the migration period.
     */
    private fun emitSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        // Emit to SharedFlow (new approach)
        _socketResponseFlow.tryEmit(response)

        // Emit to LiveData (deprecated, for backward compatibility)
        socketResponseLiveData.postValue(response)
    }

    /**
     * Emits a structured error to [errorFlow] and, for backward compatibility,
     * also emits the corresponding legacy [SocketResponse.error] through
     * [socketResponseFlow]. The error definition is looked up in
     * [TelnyxErrorRegistry]; unknown codes fall back to
     * [TelnyxErrorRegistry.get] with the catch-all UNEXPECTED_ERROR code (49001).
     *
     * @param code The registry error code (e.g. [TelnyxErrorRegistry] 45004).
     * @param customMessage Optional override for the registry's default message.
     */
    internal fun emitError(code: Int, customMessage: String? = null, token: com.telnyx.webrtc.sdk.observability.V2CaptureToken? = null) {
        val baseError = TelnyxErrorRegistry.get(code) ?: TelnyxErrorRegistry.get(TelnyxErrorCode.UNEXPECTED_ERROR.code)!!
        val finalError = if (customMessage != null) baseError.copy(message = customMessage) else baseError
        // Observe at the source, never from the replaying app flow or legacy response.
        runCatching { callReportV2?.error(finalError, token) }
        _errorFlow.tryEmit(finalError)
        emitSocketResponse(SocketResponse.error(finalError.message, code))
    }

    /**
     * Emits a structured warning to [warningFlow]. Unknown codes are ignored.
     *
     * @param code The registry warning code (e.g. 33001 ICE_CONNECTIVITY_LOST).
     */
    /**
     * Emits a structured warning to [warningFlow]. Unknown codes are ignored.
     *
     * @param code The registry warning code (e.g. 33001 ICE_CONNECTIVITY_LOST).
     * @param measurement Optional owned measurement (value/threshold) that tripped
     *   the warning; null stays null in the report — never a fabricated constant.
     */
    internal fun emitWarning(code: Int, token: com.telnyx.webrtc.sdk.observability.V2CaptureToken? = null,
        measurement: com.telnyx.webrtc.sdk.observability.V2WarningMeasurement? = null) {
        val warning = TelnyxWarningRegistry.get(code) ?: return
        runCatching { callReportV2?.warning(warning, token, measurement) }
        _warningFlow.tryEmit(warning)
    }

    /**
     * Maps a JSON-RPC server error code to the closest structured SDK error code.
     * @param serverCode The raw error code received from the server, or null.
     * @return The matching registry error code.
     */
    @Suppress("MagicNumber") // Legacy JSON-RPC server error codes from the Verto protocol
    private fun mapServerErrorCode(serverCode: Int?): Int = when (serverCode) {
        -32000 -> TelnyxErrorCode.LOGIN_FAILED.code // LOGIN_FAILED
        -32001 -> TelnyxErrorCode.INVALID_CREDENTIALS.code // INVALID_CREDENTIALS
        -32002 -> TelnyxErrorCode.SDP_SET_REMOTE_DESCRIPTION_FAILED.code // CODEC → SDP_SET_REMOTE_DESCRIPTION_FAILED
        -32003, -32004 -> TelnyxErrorCode.WEBSOCKET_ERROR.code // GATEWAY_TIMEOUT/FAILURE → WEBSOCKET_ERROR
        else -> TelnyxErrorCode.UNEXPECTED_ERROR.code // UNEXPECTED_ERROR
    }

    // Keeps track of all the created calls by theirs UUIDs.
    // Backed by a [ConcurrentHashMap] because this map is accessed concurrently from
    // multiple threads (OkHttp socket dispatcher, the public API surface, and call
    // state callbacks). A plain [LinkedHashMap] is not thread-safe and can throw
    // [ConcurrentModificationException] when iterated while being mutated, or
    // silently lose/corrupt entries under write contention. [ConcurrentHashMap]
    // provides lock-free, weakly-consistent iterators and atomic put/remove/get,
    // which is sufficient for the access patterns used in this class (single-key
    // reads, single-key writes, and snapshot reads via [getActiveCalls]). The
    // declared type remains [MutableMap] to preserve the existing public API.
    internal val calls: MutableMap<UUID, Call> = ConcurrentHashMap()
    private val socketCallIdByAppCallId: ConcurrentHashMap<UUID, UUID> = ConcurrentHashMap()
    private val appCallIdBySocketCallId: ConcurrentHashMap<UUID, UUID> = ConcurrentHashMap()

    // Keeps track of pending ICE candidates that arrive before remote description is set
    private val pendingIceCandidates: MutableMap<UUID, MutableList<PendingIceCandidate>> =
        mutableMapOf()

    // Transcript management for AI conversations
    private val _transcript = mutableListOf<TranscriptItem>()
    private val assistantResponseBuffers = mutableMapOf<String, StringBuilder>()

    // Current widget settings from AI conversation
    private var _currentWidgetSettings: WidgetSettings? = null

    // SharedFlow for transcript updates
    private val _transcriptUpdateFlow = MutableSharedFlow<List<TranscriptItem>>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the transcript updates in the form of SharedFlow
     * Contains a list of TranscriptItem objects representing the conversation
     */
    val transcriptUpdateFlow: SharedFlow<List<TranscriptItem>> =
        _transcriptUpdateFlow.asSharedFlow()
    
    // SharedFlow for connection metrics updates
    private val _socketConnectionMetricsFlow = MutableSharedFlow<SocketConnectionMetrics>(
        replay = 1,
        extraBufferCapacity = 64
    )
    
    /**
     * Returns the connection metrics updates in the form of SharedFlow (recommended)
     * Contains connection quality information including latency, jitter, and overall quality score
     * Note: This flow emits updates whenever new connection metrics are calculated, the first calculation will only occur after the first ping-pong round trip - 30 seconds after connection
     * @see [SocketConnectionMetrics]
     * @see [SocketConnectionQuality]
     */
    val socketConnectionMetricsFlow: SharedFlow<SocketConnectionMetrics> = _socketConnectionMetricsFlow.asSharedFlow()
    
    // Store the latest connection metrics
    private var currentSocketConnectionMetrics: SocketConnectionMetrics? = null

    /**
     * Returns the current transcript as an immutable list
     */
    val transcript: List<TranscriptItem>
        get() = _transcript.toList()

    /**
     * Returns the current widget settings from AI conversation
     */
    val currentWidgetSettings: WidgetSettings?
        get() = _currentWidgetSettings

    @Deprecated("telnyxclient.call is deprecated. Use telnyxclient.[option] instead. e.g telnyxclient.newInvite()")
    val call: Call? by lazy {
        if (calls.isNotEmpty()) {
            val allCalls = calls.values
            val activeCall = allCalls.firstOrNull { it.callStateFlow.value == CallState.ACTIVE }
            activeCall ?: allCalls.first() // return the first
        } else {
            buildCall()
        }
    }


    private var isCallPendingFromPush: Boolean = false
    private var pushMetaData: PushMetaData? = null
    private var pendingPushAppCallId: UUID? = null
    // Telemetry-only identity. Never reuse attachCallId, which controls actual RPC error routing.
    private var pushTelemetryAttachAttempt: Pair<UUID, UUID>? = null
    private var pushInviteTimeoutJob: Job? = null
    internal var pushInviteTimeoutMs: Long = PUSH_INVITE_TIMEOUT_MS

    /**
     * Processes an incoming call notification from a push message.
     *
     * @param metaData The push notification metadata containing call information
     */
    /** Records an app-owned real receipt/action without associating it with another active call. */
    fun recordCallReportV2PushEvent(event: com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent) {
        try { callReportV2?.pushEvent(event) } catch (_: Exception) { /* Telemetry cannot fail call handling. */ }
    }

    /**
     * Waits at most 1500ms for already-observed V2 events to leave the local pending queue.
     * Best effort only: true is NOT a server acknowledgement or proof of persistence.
     * Intended for background cleanup after the call action has completed, not its critical path.
     */
    suspend fun awaitCallReportV2Drain(timeoutMs: Long = 1500L): Boolean {
        val reporter = callReportV2 ?: return true
        val boundedTimeout = timeoutMs.coerceIn(0L, 1500L)
        return try {
            withTimeoutOrNull(boundedTimeout) { reporter.awaitDrain(boundedTimeout) } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { false }
    }

    private fun reportPushMilestone(fact: com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact,
        callId: UUID?, reason: com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Reason? = null) {
        if (callId != null) {
            val origin = if (fact.name.startsWith("PUSH_ATTACH_")) synchronized(this) {
                pushTelemetryAttachAttempt?.takeIf { it.first == callId }?.second
            } else null
            recordCallReportV2PushEvent(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent(
                fact, callId, reason = reason,
                originId = origin ?: com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.originFor(fact, callId)))
        }
    }

    /** Login's attach_call=true is an actual attach attempt too; gathering metadata alone is not. */
    private fun reportPendingPushAttachStarted(requestId: String) {
        val origin = requestId.toUuidOrNull() ?: return
        val callId = synchronized(this) {
            if (!isCallPendingFromPush) return@synchronized null
            val pending = pushMetaData?.callId.toUuidOrNull() ?: pendingPushAppCallId ?: return@synchronized null
            pushTelemetryAttachAttempt = pending to origin
            pending
        }
        reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_ATTACH_STARTED, callId)
    }

    private fun processCallFromPush(metaData: PushMetaData, pushWhenActive: Boolean = false) {
        reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_METADATA_PROCESSED,
            metaData.callId.toUuidOrNull())
        v2Logger.d("processCallFromPush PushMetaData", metaData.toJson())
        // Write pending push state under the same synchronized(this) lock used by
        // claimPendingPushCall(), clearPendingPushCall(), inviteAppCallId(), and
        // startPushInviteTimeout() so the compound invariant across these fields
        // is preserved atomically.
        synchronized(this) {
            isCallPendingFromPush = true
            this.pushMetaData = metaData
            pushTelemetryAttachAttempt = null
            // Push path: use the app-visible push call_id and remap it to the socket callID when INVITE arrives.
            // Pass the config's pushWhenActive directly because the session config may not be saved yet
            // (tokenLogin/credentialLogin runs after processCallFromPush in the connect() flow).
            pendingPushAppCallId = pushAppCallId(metaData, pushWhenActive)
        }
    }

    /**
     * Build a call containing all required parameters.
     * Will return null if there has been no session established (No successful connection and login)
     * @return [Call]
     */
    private fun buildCall(): Call? {
        if (!BuildConfig.IS_TESTING.get()) {
            sessid.let {
                return Call(
                    context,
                    this,
                    socket,
                    sessid,
                    audioManager!!,
                    providedTurn!!,
                    providedStun!!,
                )
            }
        } else {
            // We are testing, and will instead return a mocked call.
            return null
        }
    }

    /**
     * Flag to indicate whether to prefetch ICE candidates
     */
    var prefetchIceCandidates: Boolean = false
        private set

    /**
     * Set the flag to indicate whether to prefetch ICE candidates
     * @param value The new value for prefetchIceCandidates
     */
    fun setPrefetchIceCandidates(value: Boolean) {
        prefetchIceCandidates = value
    }

    /**
     * Accepts an incoming call invitation.
     *
     * @param callId The unique identifier of the incoming call
     * @param destinationNumber The phone number or SIP address that received the call
     * @param customHeaders Optional custom SIP headers to include in the response
     * @param debug When true, enables real-time call quality metrics
     * @param audioConstraints Optional audio processing constraints for the call. Controls echo
     * cancellation, noise suppression, and automatic gain control. If null, defaults to all enabled.
     * @param mutedMicOnStart When true, starts the call with the microphone muted
     * @param answeredDeviceToken Optional device token to identify which device answered a push call.
     * When null (the default), the SDK reuses the FCM push token configured on the active session
     * (via [CredentialConfig.fcmToken] / [TokenConfig.fcmToken]) so apps do not need to plumb the
     * device token through `acceptCall` for the common push-when-active flow. An explicit non-blank
     * value passed by the app always takes precedence. Blank or empty values are ignored. When no
     * usable token is available (no explicit value and no configured FCM token, or only a blank
     * value), the field is omitted from the `telnyx_rtc.answer` payload.
     * @return The [Call] instance representing the accepted call
     */
    fun acceptCall(
        callId: UUID,
        destinationNumber: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        audioConstraints: AudioConstraints? = null,
        mutedMicOnStart: Boolean = false,
        answeredDeviceToken: String? = null
    ): Call {
        // VSDK-431: reuse the configured FCM push token for push-when-active flows so apps
        // don't have to thread `answeredDeviceToken` through `acceptCall`. Explicit non-blank
        // caller-provided values still win; null/blank inputs fall back to the session's
        // configured push token, and only the resulting non-blank value is sent.
        val resolvedAnsweredDeviceToken = resolveAnsweredDeviceToken(answeredDeviceToken)
        val signalingCallId = signalingCallId(callId)
        if (signalingCallId != callId) {
            return acceptCall(
                callId = signalingCallId,
                destinationNumber = destinationNumber,
                customHeaders = customHeaders,
                debug = debug,
                audioConstraints = audioConstraints,
                mutedMicOnStart = mutedMicOnStart,
                answeredDeviceToken = answeredDeviceToken
            )
        }

        val socketPortalDebug = isSocketDebug

        val acceptCall =
            calls[callId] ?: throw IllegalStateException("Call not found for ID: $callId")

        // JS parity (VSDK-393): warn when accepting a call while other
        // non-terminal calls are still active (ANSWER_WHILE_PEER_ACTIVE).
        val otherActiveCalls = calls.values.filter { it.currentSignalingCallId() != callId }
        if (otherActiveCalls.isNotEmpty()) {
            Logger.w(
                message = "Answering call $callId while ${otherActiveCalls.size} other call(s) active"
            )
            emitWarning(TelnyxWarningCode.ANSWER_WHILE_PEER_ACTIVE.code, acceptCall.v2CaptureToken)
        }

        // The mode was captured when the inbound Call was created. Changing
        // the client default while it gathers candidates affects only future calls.
        val acceptUseTrickleIce = acceptCall.useTrickleIce

        // Update the per-call tracker's ICE mode since the inbound tracker was
        // started in onOfferReceived with the previous client-level value.
        latencyTracker.updateIceMode(callId, acceptUseTrickleIce)

        // Start benchmark for inbound call
        CallTimingBenchmark.start(isOutbound = false)
        CallTimingBenchmark.mark("accept_call_started")
        
        // Mark that user initiated answering the call (for answer delay calculation)
        // Note: Tracking already started in onOfferReceived when invite was received
        latencyTracker.markAnswerInitiated(callId)
        callReportV2?.timing(acceptCall.v2CaptureToken, "answer_called_ms")

        // Use apply block to get the correct context for Call members/extensions
        acceptCall.apply {
            val originalOfferSdp = inviteResponse?.sdp
            if (originalOfferSdp == null) {
                v2Logger.e(message = "Cannot accept call $callId, original offer SDP is missing.")
                updateCallState(CallState.ERROR)
                CallTimingBenchmark.reset()
                client.latencyTracker.cancelCallTracking(callId)
                return@apply
            }

            // Actions to perform immediately
            client.stopMediaPlayer()
            setSpeakerMode(speakerState)
            client.callOngoing()

            // Set microphone mute state if requested
            if (mutedMicOnStart) {
                setMuteState(true)
            }

            // Start local audio capture with provided constraints before creating answer
            peerConnection?.startLocalAudioCapture(audioConstraints)
            
            // Mark peer setup complete (after media acquired)
            client.latencyTracker.markPeerSetupComplete(callId)

            // Create the answer SDP
            peerConnection?.answer(AppSdpObserver())

            // Helper function to send answer immediately for trickle ICE
            fun sendAnswerImmediately() {
                val generatedAnswerSdp = peerConnection?.getLocalDescription()?.description
                if (generatedAnswerSdp == null) {
                    client.emitError(TelnyxErrorCode.SDP_CREATE_ANSWER_FAILED.code, token = v2CaptureToken) // SDP_CREATE_ANSWER_FAILED
                    updateCallState(CallState.ERROR)
                    v2Logger.e(message = "Failed to generate local description (Answer SDP) for call $callId")
                } else {
                    // Use the SdpUtils to modify the SDP
                    var finalAnswerSdp = SdpUtils.modifyAnswerSdpToIncludeOfferCodecs(
                        originalOfferSdp,
                        generatedAnswerSdp
                    )

                    // Add trickle ICE capability to SDP
                    finalAnswerSdp = SdpUtils.addTrickleIceCapability(finalAnswerSdp)

                    v2Logger.d(
                        tag = "AcceptCall",
                        message = "Sending answer immediately with trickle ICE for call $callId"
                    )

                    val uuid: String = UUID.randomUUID().toString()
                    val answerBodyMessage = SendingMessageBody(
                        uuid, SocketMethod.ANSWER.methodName,
                        CallParams(
                            sessid = sessionId,
                            sdp = finalAnswerSdp,
                            answeredDeviceToken = resolvedAnsweredDeviceToken,
                            dialogParams = CallDialogParams(
                                callId = callId,
                                destinationNumber = destinationNumber,
                                customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf(),
                            ),
                            trickle = true,
                            mobileSdkName = sdkIdentity.name,
                            mobileSdkVersion = sdkIdentity.version,
                        )
                    )
                    if (!socket.sendReturning(answerBodyMessage)) {
                        // JS parity (VSDK-393): send failure -> SDP_SEND_FAILED
                        Logger.e(message = "Failed to send ANSWER (trickle) for call $callId")
                        emitError(TelnyxErrorCode.SDP_SEND_FAILED.code, token = v2CaptureToken)
                    }
                    CallTimingBenchmark.mark("answer_sent")
                    v2Logger.d(tag = "ICE_DIAG", message = {
                        "ANSWER sent via socket for call $callId [${SystemClock.elapsedRealtime()}ms]"
                    })
                    client.debugDataCollector.onIceDiagnostic(
                        callId = callId,
                        event = "answer_sent",
                        fields = mapOf("mode" to "trickle"),
                        tsMs = SystemClock.elapsedRealtime(),
                    )
                    client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_ANSWER_SENT)

                    // Flush queued ICE candidates after sending ANSWER (for trickle ICE)
                    peerConnection?.flushQueuedCandidatesAfterAnswer()

                    updateCallState(CallState.ACTIVE)
                    
                    // Complete call latency tracking
                    client.debugDataCollector.addCallTimingsLogs(callId, client.latencyTracker.completeCallTracking(callId))

                    // Start stats collection - interval is adjusted internally based on debug flags
                    if (getWebRTCReporter(callId) == null) {
                        val webRTCReporter = WebRTCReporter(
                            socket,
                            callId,
                            getTelnyxLegId()?.toString(),
                            peerConnection!!,
                            debug,
                            socketPortalDebug,
                            enableCallQualityReports,
                            callQualityReportInterval,
                            sendWebRTCStatsViaSocket,
                            debugDataCollector,
                            v2CaptureToken
                        )
                        webRTCReporter.onCallQualityChange = { metrics ->
                            onCallQualityChange?.invoke(metrics)
                        }
                        webRTCReporter.startStats()
                        addWebRTCReporter(callId, webRTCReporter)
                    }

                    v2Logger.d(
                        tag = "AcceptCall",
                        message = "Answer sent successfully with trickle ICE for call $callId"
                    )
                }
            }

            launchAcceptCallJob(callId) {
                try {
                    if (acceptUseTrickleIce) {
                        // For trickle ICE, send ANSWER immediately without waiting for candidates
                        v2Logger.d(
                            tag = "AcceptCall",
                            message = "Trickle ICE enabled. Sending answer immediately for call $callId"
                        )

                        sendAnswerImmediately()
                    } else {
                        // For traditional ICE, wait for candidates
                        v2Logger.d(tag = "AcceptCall", message = "Waiting for first ICE candidate...")
                        peerConnection?.firstCandidateDeferred?.await() // Wait for first candidate
                        v2Logger.d(
                            tag = "AcceptCall",
                            message = "First ICE candidate received. Setting up negotiation complete callback."
                        )

                        // Now set the callback for when ICE negotiation stabilizes (timer expires)
                        peerConnection?.setOnNegotiationComplete { // This also starts the negotiation timer
                            v2Logger.d(
                                tag = "AcceptCall",
                                message = "ICE negotiation complete. Proceeding to send answer."
                            )
                            val generatedAnswerSdp =
                                peerConnection?.getLocalDescription()?.description
                            if (generatedAnswerSdp == null) {
                                client.emitError(TelnyxErrorCode.SDP_CREATE_ANSWER_FAILED.code, token = v2CaptureToken) // SDP_CREATE_ANSWER_FAILED
                                updateCallState(CallState.ERROR)
                                v2Logger.e(message = "Failed to generate local description (Answer SDP) after negotiation for call $callId")
                            } else {
                                // Use the SdpUtils to modify the SDP
                                var finalAnswerSdp = SdpUtils.modifyAnswerSdpToIncludeOfferCodecs(
                                    originalOfferSdp,
                                    generatedAnswerSdp
                                )

                                // Add trickle ICE capability to SDP if trickle ICE is enabled
                                if (acceptUseTrickleIce) {
                                    finalAnswerSdp =
                                        SdpUtils.addTrickleIceCapability(finalAnswerSdp)
                                }

                                v2Logger.d(
                                    tag = "SDP_Modify",
                                    message = "[Original Answer SDP After Wait]:\n$generatedAnswerSdp"
                                )
                                v2Logger.d(
                                    tag = "SDP_Modify",
                                    message = "[Final Answer SDP After Wait]:\n$finalAnswerSdp"
                                )

                                val uuid: String = UUID.randomUUID().toString()
                                val answerBodyMessage = SendingMessageBody(
                                    uuid, SocketMethod.ANSWER.methodName,
                                    CallParams(
                                        sessid = sessionId,
                                        sdp = finalAnswerSdp,
                                        answeredDeviceToken = resolvedAnsweredDeviceToken,
                                        dialogParams = CallDialogParams(
                                            callId = callId,
                                            destinationNumber = destinationNumber,
                                            customHeaders = customHeaders?.toCustomHeaders()
                                                ?: arrayListOf(),
                                        ),
                                        trickle = if (acceptUseTrickleIce) true else null,
                                        mobileSdkName = sdkIdentity.name,
                                        mobileSdkVersion = sdkIdentity.version,
                                    )
                                )
                                if (!socket.sendReturning(answerBodyMessage)) {
                                    // JS parity (VSDK-393): send failure -> SDP_SEND_FAILED
                                    Logger.e(message = "Failed to send ANSWER (non-trickle) for call $callId")
                                    emitError(TelnyxErrorCode.SDP_SEND_FAILED.code, token = v2CaptureToken)
                                }
                                CallTimingBenchmark.mark("answer_sdp_sent")
                                v2Logger.d(tag = "ICE_DIAG", message = {
                                    "ANSWER sent (non-trickle) via socket for call $callId [${SystemClock.elapsedRealtime()}ms]"
                                })
                                client.debugDataCollector.onIceDiagnostic(
                                    callId = callId,
                                    event = "answer_sent",
                                    fields = mapOf("mode" to "non_trickle"),
                                    tsMs = SystemClock.elapsedRealtime(),
                                )
                                client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_ANSWER_SENT)
                                updateCallState(CallState.ACTIVE)
                                
                                // Complete call latency tracking
                                client.debugDataCollector.addCallTimingsLogs(callId, client.latencyTracker.completeCallTracking(callId))

                                // Start stats collection - interval is adjusted internally based on debug flags
                                if (getWebRTCReporter(callId) == null) {
                                    val webRTCReporter = WebRTCReporter(
                                        socket,
                                        callId,
                                        getTelnyxLegId()?.toString(),
                                        peerConnection!!,
                                        debug,
                                        socketPortalDebug,
                                        enableCallQualityReports,
                                        callQualityReportInterval,
                            sendWebRTCStatsViaSocket,
                                        debugDataCollector,
                                        v2CaptureToken
                                    )
                                    webRTCReporter.onCallQualityChange = { metrics ->
                                        onCallQualityChange?.invoke(metrics)
                                    }
                                    webRTCReporter.startStats()
                                    addWebRTCReporter(callId, webRTCReporter)
                                }

                                v2Logger.d(
                                    tag = "AcceptCall",
                                    message = "Answer sent successfully for call $callId"
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    v2Logger.d(
                        tag = "AcceptCall",
                        message = "Async accept process cancelled for call $callId",
                        throwable = e
                    )
                } catch (e: Exception) {
                    v2Logger.e(
                        tag = "AcceptCall",
                        message = "Error during async accept process for call $callId: ${e.message}"
                    )
                    updateCallState(CallState.ERROR)
                }
            }
        }

        this.addToCalls(acceptCall) // Keep this outside apply if needed, or it's implicitly done
        // Return the call object immediately (non-blocking)
        return acceptCall
    }

    /**
     * Resolves the device token to include in `telnyx_rtc.answer` for push-when-active flows.
     *
     * Preference order (each step returns the first non-blank value):
     *  1. An explicit non-blank `explicit` value supplied by the caller.
     *  2. The FCM push token configured on the active session via
     *     [CredentialConfig.fcmToken] or [TokenConfig.fcmToken].
     *
     * Returns `null` when neither source yields a non-blank value. Callers should only
     * include the resulting token in the verto payload; a `null` result omits
     * `answered_device_token` entirely (Gson drops null fields) so the answer JSON
     * stays clean for the push-when-active-disabled case.
     *
     * Marked `internal` (not `private`) so unit tests in the same module can exercise
     * the resolution rules without standing up the full `acceptCall` peer-connection
     * machinery.
     */
    internal fun resolveAnsweredDeviceToken(explicit: String?): String? {
        explicit?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val configured = (credentialSessionConfig?.fcmToken ?: tokenSessionConfig?.fcmToken)
        return configured?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun pushWhenActiveEnabled(): Boolean =
        credentialSessionConfig?.pushWhenActive == true || tokenSessionConfig?.pushWhenActive == true

    private fun String?.toUuidOrNull(): UUID? =
        this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun optionalUuid(params: JsonObject, key: String): UUID? =
        params.get(key)
            ?.takeIf { !it.isJsonNull }
            ?.asString
            .toUuidOrNull()

    internal fun reattachIdentifiers(
        params: JsonObject,
        existingSessionId: UUID?,
        existingLegId: UUID?
    ): Pair<UUID?, UUID?> =
        Pair(
            optionalUuid(params, "telnyx_session_id") ?: existingSessionId,
            optionalUuid(params, "telnyx_leg_id") ?: existingLegId
        )

    private fun pushAppCallId(metaData: PushMetaData, pushWhenActive: Boolean = false): UUID? {
        if (!pushWhenActive && !pushWhenActiveEnabled()) return null
        return metaData.callId.toUuidOrNull()
    }

    private fun inviteVariables(params: JsonObject): Map<String, String> {
        val variablesElement = params.get("variables")
        if (variablesElement?.isJsonObject != true) return emptyMap()
        val variables = variablesElement.asJsonObject
        return variables.entrySet().mapNotNull { (key, value) ->
            value.takeIf { !it.isJsonNull }?.asString?.let { key to it }
        }.toMap()
    }

    private fun inviteAppCallId(socketCallId: UUID): UUID? {
        if (!pushWhenActiveEnabled()) return null
        // Only remap when the SDK already learned the app-facing ID from the push path.
        // Read under synchronized to avoid a TOCTOU race with claimPendingPushCall()
        // or clearPendingPushCall() which mutate these fields under the same lock.
        return synchronized(this) {
            pendingPushAppCallId.takeIf { isCallPendingFromPush }
                ?: appCallIdBySocketCallId[socketCallId]
        }
    }


    /**
     * Creates a new outgoing call invitation.
     *
     * @param callerName The name of the caller to display
     * @param callerNumber The phone number of the caller
     * @param destinationNumber The phone number or SIP address to call
     * @param clientState Additional state information to pass with the call
     * @param customHeaders Optional custom SIP headers to include with the call
     *
     *   Note that if you are calling a Telnyx AI Assistant,  Headers with the `X-` prefix
     *   will be mapped to dynamic variables in the AI assistant (e.g., `X-Account-Number` becomes `{{account_number}}`).
     *   Note: Hyphens in header names are converted to underscores in variable names.
     *
     * @param debug When true, enables real-time call quality metrics
     * @param preferredCodecs Optional list of preferred audio codecs for the call
     * @param audioConstraints Optional audio processing constraints for the call. Controls echo
     * cancellation, noise suppression, and automatic gain control. If null, defaults to all enabled.
     * @param mutedMicOnStart When true, starts the call with the microphone muted
     *
     * ### Audio Processing Constraints
     *
     * Audio processing constraints can be set by passing an `audioConstraints` object to control
     * echo cancellation, noise suppression, and automatic gain control. These settings align with
     * the W3C MediaTrackConstraints specification.
     *
     * Example:
     * ```kotlin
     * client.newInvite(
     *     callerName = "John Doe",
     *     callerNumber = "+1234567890",
     *     destinationNumber = "+0987654321",
     *     clientState = "",
     *     audioConstraints = AudioConstraints(
     *         echoCancellation = true,
     *         noiseSuppression = true,
     *         autoGainControl = true
     *     )
     * )
     * ```
     *
     * @return A new [Call] instance representing the outgoing call
     */
    fun newInvite(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        preferredCodecs: List<AudioCodec>? = null,
        audioConstraints: AudioConstraints? = null,
        mutedMicOnStart: Boolean = false
    ): Call {
        val callDebug = debug
        val socketPortalDebug = isSocketDebug
        val inviteCallId: UUID = UUID.randomUUID()

        // Trickle ICE is session-wide: read the value configured on the client
        // (set at login via CredentialConfig/TokenConfig, or through setUseTrickleIce()).
        val inviteUseTrickleIce = useTrickleIce

        // Start latency tracking for outbound call
        latencyTracker.startCallTracking(inviteCallId, isOutbound = true, useTrickleIce = inviteUseTrickleIce)
        
        val inviteCall = Call(
            context = context,
            client = this,
            socket = socket,
            sessionId = sessid,
            audioManager = audioManager!!,
            providedTurn = providedTurn!!,
            providedStun = providedStun!!
        ).apply {
            callId = inviteCallId
            beginV2Capture(inbound = false, callerName = callerName, callerNumber = callerNumber,
                destinationNumber = destinationNumber, clientState = clientState,
                customHeaders = customHeaders, preferredCodecs = preferredCodecs)
            updateCallState(CallState.RINGING)

            // Notify debug data collector that a new call has started
            client.debugDataCollector.onCallStarted(inviteCallId)
            client.debugDataCollector.updateCallMetadata(
                callId = inviteCallId,
                callerNumber = callerNumber,
                destinationNumber = destinationNumber,
                direction = "outbound"
            )

            peerConnection = Peer(
                context,
                client,
                providedTurn,
                providedStun,
                inviteCallId,
                prefetchIceCandidates,
                getForceRelayCandidate(),
                isAnswering = false,
                onIceCandidateAdd = { candidate ->
                    addIceCandidateInternal(candidate)
                },
                audioConstraints = audioConstraints,
                initialV2CaptureToken = v2CaptureToken
            ).also {
                it.v2CaptureToken = v2CaptureToken

                // Create reporter - interval is adjusted internally based on debug flags
                val webRTCReporter =
                    WebRTCReporter(
                        socket,
                        callId,
                        this.getTelnyxLegId()?.toString(),
                        it,
                        callDebug,
                        socketPortalDebug,
                        enableCallQualityReports,
                        callQualityReportInterval,
                            sendWebRTCStatsViaSocket,
                        client.debugDataCollector,
                        v2CaptureToken
                    )
                webRTCReporter.onCallQualityChange = { metrics ->
                    onCallQualityChange?.invoke(metrics)
                }
                webRTCReporter.startStats()
                addWebRTCReporter(callId, webRTCReporter)
            }

            peerConnection?.startLocalAudioCapture(audioConstraints)

            // Set microphone mute state
            setMuteState(mutedMicOnStart)

            // Apply codec preferences before creating the offer
            peerConnection?.applyAudioCodecPreferences(preferredCodecs)
            
            // Mark peer setup complete (after media acquired and codec preferences applied)
            client.latencyTracker.markPeerSetupComplete(inviteCallId)

            startOutgoingCallInternal(
                callerName = callerName,
                callerNumber = callerNumber,
                destinationNumber = destinationNumber,
                clientState = clientState,
                customHeaders = customHeaders,
                preferredCodecs = preferredCodecs
            )

            client.callOngoing()
            client.playRingBackTone()
        }
        this.addToCalls(inviteCall)
        return inviteCall
    }


    /**
     * Ends an ongoing call with a provided callID, the unique UUID belonging to each call
     * @param callId, the callId provided with the invitation
     * @see [Call]
     */
    fun endCall(callId: UUID) {
        val signalingCallId = signalingCallId(callId)
        val appFacingCallId = appCallId(signalingCallId)
        val endCall = calls[signalingCallId]
        endCall?.apply {
            callReportV2?.termination(v2CaptureToken, local = true)
            val uuid: String = UUID.randomUUID().toString()
            // Determine cause code and name based on call state
            val (causeCode, causeName) = when (callStateFlow.value) {
                // When Active or Connecting, use NORMAL_CLEARING
                CallState.ACTIVE -> CauseCode.NORMAL_CLEARING.code to CauseCode.NORMAL_CLEARING.name
                // When Ringing (ie. Rejecting an incoming call), use USER_BUSY
                CallState.RINGING, CallState.CONNECTING -> CauseCode.USER_BUSY.code to CauseCode.USER_BUSY.name
                // Default to NORMAL_CLEARING for other states
                else -> CauseCode.NORMAL_CLEARING.code to CauseCode.NORMAL_CLEARING.name
            }
            val terminationReason = CallTerminationReason(cause = causeName, causeCode = causeCode)

            v2Logger.d(
                tag = "EndCall",
                message = "Ending call with ID: $signalingCallId, current state: ${callStateFlow.value}, cause: $causeName ($causeCode)"
            )

            val byeMessageBody = SendingMessageBody(
                uuid, SocketMethod.BYE.methodName,
                ByeParams(
                    sessionId,
                    causeCode,
                    causeName,
                    ByeDialogParams(
                        signalingCallId
                    )
                )
            )

            val byeResponseForUi = ByeResponse(
                callId = appFacingCallId,
                cause = causeName,
                causeCode = causeCode
                // sipCode and sipReason are null here
            )
            // send bye message to the UI
            client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.BYE.methodName,
                        byeResponseForUi
                    )
                )
            )
            updateCallState(CallState.DONE(terminationReason))

            // Stop reporter before releasing the peer connection
            removeWebRTCReporter(signalingCallId)?.stopStats()

            // Log debug data for the ended call
            client.debugDataCollector.onCallEnded(signalingCallId, causeName)

            client.removeFromCalls(signalingCallId)
            client.callNotOngoing()
            if (!socket.sendReturning(byeMessageBody)) {
                // JS parity (VSDK-393): BYE send failure -> BYE_SEND_FAILED
                Logger.e(message = "Failed to send BYE for call $signalingCallId")
                client.emitError(TelnyxErrorCode.BYE_SEND_FAILED.code, token = v2CaptureToken)
            }
            resetCallOptions()
            client.stopMediaPlayer()
            peerConnection?.release()
            peerConnection = null
            answerResponse = null
            inviteResponse = null
            _transcript.clear()
            _transcriptUpdateFlow.tryEmit(emptyList())
        }
    }

    /**
     * Add specified call to the calls MutableMap
     * @param call, and instance of [Call]
     */
    internal fun addToCalls(call: Call) {
        // JS parity (VSDK-393): warn when a call is added while another
        // non-terminal call is already in progress.
        val otherActiveCalls = calls.values.filter { it.currentSignalingCallId() != call.currentSignalingCallId() }
        if (otherActiveCalls.isNotEmpty()) {
            Logger.w(
                message = "Multiple active calls detected: ${otherActiveCalls.size} existing call(s) while adding ${call.currentSignalingCallId()}"
            )
            emitWarning(TelnyxWarningCode.MULTIPLE_ACTIVE_CALLS_DETECTED.code)
        }
        calls[call.currentSignalingCallId()] = call
    }

    internal fun registerCallIdAlias(appCallId: UUID, socketCallId: UUID) {
        synchronized(this) {
            if (appCallId == socketCallId) {
                pendingPushAppCallId = null
                return
            }
            socketCallIdByAppCallId[appCallId] = socketCallId
            appCallIdBySocketCallId[socketCallId] = appCallId
            pendingPushAppCallId = null
        }
    }

    internal fun signalingCallId(appCallId: UUID): UUID = socketCallIdByAppCallId[appCallId] ?: appCallId

    internal fun appCallId(socketCallId: UUID): UUID = appCallIdBySocketCallId[socketCallId] ?: socketCallId

    /**
     * Remove specified call from the calls MutableMap
     * @param callId, the UUID used to identify a specific
     */
    internal fun removeFromCalls(callId: UUID) {
        cancelAcceptCallJob(callId)
        forceRelayRecoveredCallIds.remove(callId)
        forceRelayRecoveredCallIds.remove(signalingCallId(callId))
        calls.remove(callId)
        appCallIdBySocketCallId.remove(callId)?.let { socketCallIdByAppCallId.remove(it) }
        socketCallIdByAppCallId.remove(callId)?.let { appCallIdBySocketCallId.remove(it) }
        if (calls.isEmpty()) {
            clearPendingPushCall()
        }
        
        // Clean up latency tracking for this call
        latencyTracker.cancelCallTracking(callId)

        // Clean up any pending ICE candidates for this call
        synchronized(pendingIceCandidates) {
            val removedCandidates = pendingIceCandidates.remove(callId)
            removedCandidates?.let {
                v2Logger.d(
                    tag = "removeFromCalls",
                    message = "Cleaned up ${it.size} pending ICE candidates for ended call $callId"
                )
            }
        }
    }

    private var socketReconnection: TxSocket? = null

    internal var isNetworkCallbackRegistered = false
    internal fun observeV2Network() {
        runCatching {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = manager.activeNetwork
            val caps = manager.getNetworkCapabilities(network)
            val type = when {
                network == null -> "NONE"
                caps == null -> "UNKNOWN"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                else -> "UNKNOWN"
            }
            callReportV2?.network(type, caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        }
    }
    private val networkCallback = object : ConnectivityHelper.NetworkCallback() {
        override fun onCapabilitiesChanged(network: android.net.Network, capabilities: android.net.NetworkCapabilities) {
            observeV2Network()
            super.onCapabilitiesChanged(network, capabilities)
        }
        override fun onNetworkAvailable() {
            observeV2Network()
            if (isDeclinePushConnection) {
                return
            }

            v2Logger.d(
                message = v2Logger.formatMessage(
                    "[%s] :: There is a network available",
                    this@TelnyxClient.javaClass.simpleName
                )
            )
            // User has been logged in
            resetGatewayCounters()
            if (reconnecting && (credentialSessionConfig != null || tokenSessionConfig != null)) {
                launchReconnectJob()
            }
        }

        override fun onNetworkUnavailable() {
            observeV2Network()
            if (isDeclinePushConnection) {
                return
            }

            v2Logger.d(
                message = v2Logger.formatMessage(
                    "[%s] :: There is no network available",
                    this@TelnyxClient.javaClass.simpleName
                )
            )
            notifyCallPeersOfNetworkPathChange()
            reconnecting = true

            Handler(Looper.getMainLooper()).postDelayed({
                if (!ConnectivityHelper.isNetworkEnabled(context)) {
                    getActiveCalls().forEach { (_, call) ->
                        call.updateCallState(CallState.DROPPED(CallNetworkChangeReason.NETWORK_LOST))
                    }

                    emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE

                    // Start the reconnection timer to track timeout
                    startReconnectionTimer()
                } else {
                    //Network is switched here. Either from Wifi to LTE or vice-versa
                    launchReconnectJob()
                }
            }, RECONNECT_DELAY)
        }
    }

    private fun notifyCallPeersOfNetworkPathChange() {
        calls.values
            .mapNotNull { it.peerConnection }
            .toSet()
            .forEach { it.onNetworkPathDidChange() }
    }

    private fun launchReconnectJob(delayMs: Long = 0L) {
        reconnectJob?.cancel()
        val job = clientScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            if (reconnecting) {
                reconnectToSocket()
            }
        }
        reconnectJob = job
        job.invokeOnCompletion {
            if (reconnectJob === job) {
                reconnectJob = null
            }
        }
    }

    /**
     * JS parity (VSDK-393): mirrors BaseSession._checkTokenExpiry.
     *
     * Parses the login_token JWT and emits TOKEN_EXPIRING_SOON 120s before it
     * expires. Called after every successful login response (including
     * re-authentications), so a refreshed token re-arms the timer.
     *
     * - Already-expired tokens are skipped silently — the gateway will reject
     *   the login with LOGIN_FAILED via the normal error path.
     * - Tokens expiring within the 120s window emit immediately.
     * - Invalid / non-JWT tokens are skipped silently (no warning).
     */
    private fun checkTokenExpiry() {
        cancelTokenExpiryTimeout()
        val secondsUntilExpiry = JwtUtils.secondsUntilExpiry(tokenSessionConfig?.sipToken)
        val delayMs = (secondsUntilExpiry ?: 0L).let { remaining ->
            (remaining - JwtUtils.TOKEN_EXPIRY_WARNING_SECONDS) * JwtUtils.MS_PER_SECOND
        }
        when {
            // Unknown expiry — nothing to schedule.
            secondsUntilExpiry == null -> Unit
            // Already expired — the login response will fail with LOGIN_FAILED.
            delayMs <= 0L && secondsUntilExpiry <= 0L -> Unit
            // Expiring within the warning window — emit immediately.
            delayMs <= 0L -> emitWarning(TelnyxWarningCode.TOKEN_EXPIRING_SOON.code)
            else -> tokenExpiryJob = clientScope.launch {
                delay(delayMs)
                tokenExpiryJob = null
                emitWarning(TelnyxWarningCode.TOKEN_EXPIRING_SOON.code)
            }
        }
    }

    /** JS parity (VSDK-393): mirrors BaseSession._clearTokenExpiryTimeout. */
    private fun cancelTokenExpiryTimeout() {
        tokenExpiryJob?.cancel()
        tokenExpiryJob = null
    }

    /**
     * Reconnect to the Telnyx socket using saved Telnyx Config - either Token or Credential based
     * @see [TxSocket]
     * @see [TelnyxConfig]
     */
    @Suppress("ComplexMethod")
    private suspend fun reconnectToSocket() = withContext(Dispatchers.Default) {
        val credentialConfig = credentialSessionConfig
        val tokenConfig = tokenSessionConfig
        val hasReconnectConfig = credentialConfig != null || tokenConfig != null

        if (isDeclinePushConnection || !reconnecting || !hasReconnectConfig) {
            return@withContext
        }

        // Start the reconnection timer to track timeout
        startReconnectionTimer()

        // Emit reconnecting status
        emitConnectionStatus(ConnectionStatus.RECONNECTING)

        //Disconnect active calls for reconnection
        getActiveCalls().forEach { (_, call) ->
            webRTCReportersMap.forEach { (_, webRTCReporter) ->
                webRTCReporter.pauseStats()
            }
            call.peerConnection?.disconnect()
            call.updateCallState(CallState.RECONNECTING(CallNetworkChangeReason.NETWORK_SWITCH))
        }

        //Delay for network to be properly established
        delay(RECONNECT_DELAY)
        if (isDeclinePushConnection || !reconnecting) {
            return@withContext
        }

        val generation = nextConnectionGeneration()
        val reconnectSocket = TxSocket(
            socket.host_address,
            socket.port
        )
        cancelSocketConnectJob()
        socketReconnection = reconnectSocket
        // Cancel old socket coroutines
        socket.cancel("TxSocket destroyed, initializing new socket and connecting.")
        // Destroy old socket
        socket.destroy()
        launchSocketConnect {
            if (isDeclinePushConnection || !reconnecting || connectionGeneration.get() != generation) {
                reconnectSocket.destroy()
                return@launchSocketConnect
            }

            // Socket is now the reconnectionSocket — the swap is owned by the
            // same guard as auth publication and the commit in
            // onLoginResponseConfirmedImpl so a response in flight can never
            // bind across a half-swapped socket: it either sees the old socket
            // (binds, then the newer cycle supersedes) or is rejected.
            synchronized(this) { socket = reconnectSocket }

            if (providedHostAddress == null) {
                providedHostAddress =
                    if (pushMetaData == null) Config.TELNYX_PROD_HOST_ADDRESS
                    else
                        Config.TELNYX_PROD_HOST_ADDRESS
            }

            if (voiceSDKID != null) {
                pushMetaData = PushMetaData(
                    callerName = "",
                    callerNumber = "",
                    callId = "",
                    voiceSdkId = voiceSDKID
                )
            }

            // Connect to new socket
            reconnectSocket.connectWithConnectionGuard(
                this@TelnyxClient,
                providedHostAddress,
                providedPort,
                pushMetaData,
                isCurrentConnection = { isCurrentConnectionGeneration(generation, reconnectSocket) }
            ) socketConnect@ {
                if (!isCurrentConnectionGeneration(generation, reconnectSocket)) {
                    return@socketConnect
                }

                //We can safely assume that the socket is connected at this point
                // Login with stored configuration
                credentialConfig?.let {
                    credentialLogin(it, reconnectSocket) {
                        isCurrentConnectionGeneration(generation, reconnectSocket)
                    }
                } ?: tokenConfig?.let {
                    tokenLogin(it, reconnectSocket) {
                        isCurrentConnectionGeneration(generation, reconnectSocket)
                    }
                }

                // Change an ongoing call's socket to the new socket.
                call?.let { call?.socket = reconnectSocket }
            }
        }
    }

    init {
        if (!BuildConfig.IS_TESTING.get()) {
            try { com.telnyx.webrtc.sdk.observability.AndroidV2Installer.install(this,
                com.telnyx.webrtc.sdk.observability.CallReportV2Config(), constructing = true) }
            catch (_: Exception) { /* Reporting must not prevent SDK construction. */ }
            catch (_: LinkageError) { /* Optional reporting runtime unavailable. */ }
        }
        try {
            // Generate random UUID for sessid param, convert it to string and set globally
            sessid = UUID.randomUUID().toString()

            // Initialize deprecated LiveData for backward compatibility
            socketResponseLiveData =
                MutableLiveData<SocketResponse<ReceivedMessageBody>>(SocketResponse.initialised())

            // Initialize both SharedFlow and LiveData with initial state
            emitSocketResponse(SocketResponse.initialised())

            // Initialize connection status as disconnected
            emitConnectionStatus(ConnectionStatus.DISCONNECTED)

            socket = TxSocket(
                host_address = Config.TELNYX_PROD_HOST_ADDRESS,
                port = Config.TELNYX_PORT
            )
            if (!isDeclinePushConnection) {
                registerNetworkCallback()
            }

            callReportV2CreationDurationMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - callReportV2CreatedAtMs
            runCatching { callReportV2?.creationFinished(callReportV2CreationDurationMs) }
        } catch (failure: Exception) {
            runCatching { callReportV2?.creationFailed(failure.javaClass.simpleName) }
            throw failure
        }
    }

    private var rawRingtone: Any? = null
    private var rawRingbackTone: Int? = null

    /**
     * Return the saved ringtone reference
     * @returns [Int]
     */
    /**
     * Gets the currently configured ringtone resource.
     *
     * @return The ringtone resource reference, or null if none is set
     */
    fun getRawRingtone(): Any? {
        return rawRingtone
    }

    /**
     * Return the saved ringback tone reference
     * @returns [Int]
     */
    /**
     * Gets the currently configured ringback tone resource.
     *
     * @return The ringback tone resource reference, or null if none is set
     */
    fun getRawRingbackTone(): Int? {
        return rawRingbackTone
    }

    /**
     * Gets the current trickle ICE setting
     *
     * @return true if trickle ICE is enabled, false otherwise
     */
    fun getUseTrickleIce(): Boolean {
        return useTrickleIce
    }

    /**
     * Set the flag to indicate whether to use trickle ICE.
     *
     * This applies session-wide to all subsequent calls made or accepted by this client.
     * The value is also initialized from [CredentialConfig]/[TokenConfig] at login time.
     *
     * @param enabled The new value for useTrickleIce
     */
    fun setUseTrickleIce(enabled: Boolean) {
        this.useTrickleIce = enabled
    }

    private fun clearTransientDeclinePushMode() {
        if (!isDedicatedDeclinePushClient) {
            isDeclinePushConnection = false
        }
    }

    private fun nextConnectionGeneration(): Int = connectionGeneration.incrementAndGet()

    private fun isCurrentConnectionGeneration(generation: Int, txSocket: TxSocket): Boolean {
        return connectionGeneration.get() == generation && socket === txSocket
    }

    private fun disconnectTransientDeclinePushConnection() {
        if (isDeclinePushConnection && !isDedicatedDeclinePushClient) {
            disconnect()
        }
    }

    private fun shouldIgnoreDeclinePushCallEvent(methodName: String): Boolean {
        if (!isDeclinePushConnection) {
            return false
        }

        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: Ignoring %s during decline push connection",
                this@TelnyxClient.javaClass.simpleName,
                methodName
            )
        )
        return true
    }

    /**
     * Connects to the socket using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required for push calls to work
     *
     */
    @Deprecated(
        "this telnyxclient.connect is deprecated." +
                " Use telnyxclient.connect(providedServerConfig,txPushMetaData," +
                "credential or tokenLogin) instead."
    )
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        txPushMetaData: String? = null,
    ) {

        clearTransientDeclinePushMode()
        applyCallReportV2Environment(providedServerConfig)
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        val generation = nextConnectionGeneration()
        socket.destroy()
        val connectSocket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )
        // t027: every socket replacement participates in the login-commit ownership
        // guard so a response in flight can never bind across a half-swapped socket.
        synchronized(this) { socket = connectSocket }

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            v2Logger.d(message = "Provided Host Address: $providedHostAddress")
            launchSocketConnect {
                connectSocket.connectWithConnectionGuard(
                    this@TelnyxClient,
                    providedHostAddress,
                    providedPort,
                    pushMetaData,
                    isCurrentConnection = { isCurrentConnectionGeneration(generation, connectSocket) }
                ) socketConnect@ {
                    if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                        return@socketConnect
                    }
                }
            }
        } else {
            emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE
        }
    }


    /**
     * Connects to the socket by credential and using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required fot push calls to work
     * @param credentialConfig, represents a SIP user for login - credential based
     * @param autoLogin, if true, the SDK will automatically log in with
     * the provided credentials on connection established
     * We recommend setting this to true
     *
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        credentialConfig: CredentialConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    ) {
        clearTransientDeclinePushMode()
        applyCallReportV2Environment(providedServerConfig)
        isSocketDebug = credentialConfig.debug
        enableCallQualityReports = credentialConfig.enableCallQualityReports
        callQualityReportInterval = credentialConfig.callQualityReportInterval
        sendWebRTCStatsViaSocket = credentialConfig.sendWebRTCStatsViaSocket
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(credentialConfig.logLevel, credentialConfig.customLogger)


        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata, credentialConfig.pushWhenActive)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        if (credentialConfig.region != Region.AUTO) {
            v2RegionHint = credentialConfig.region.value
            providedHostAddress = "${credentialConfig.region.value}.$providedHostAddress"
        }

        val generation = nextConnectionGeneration()
        socket.destroy()
        val connectSocket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )
        // t027: every socket replacement participates in the login-commit ownership
        // guard so a response in flight can never bind across a half-swapped socket.
        synchronized(this) { socket = connectSocket }

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            v2Logger.d(message = "Provided Host Address: $providedHostAddress")

            launchSocketConnect {
                if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                    connectSocket.destroy()
                    return@launchSocketConnect
                }

                if (credentialConfig.fallbackOnRegionFailure) {
                    providedHostAddress = ConnectivityHelper.resolveReachableHost(
                        providedHostAddress!!,
                        providedPort!!
                    )
                    v2Logger.d(message = "Verified Host Address: $providedHostAddress")
                }

                if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                    connectSocket.destroy()
                    return@launchSocketConnect
                }

                if (txPushMetaData != null) {
                    val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                    voiceSDKID = metadata.voiceSdkId
                } else if (voiceSDKID != null) {
                    pushMetaData = PushMetaData(
                        callerName = "",
                        callerNumber = "",
                        callId = "",
                        voiceSdkId = voiceSDKID
                    )
                }
                connectSocket.connectWithConnectionGuard(
                    this@TelnyxClient,
                    providedHostAddress,
                    providedPort,
                    pushMetaData,
                    isCurrentConnection = { isCurrentConnectionGeneration(generation, connectSocket) }
                ) {
                    if (autoLogin && isCurrentConnectionGeneration(generation, connectSocket)) {
                        credentialLogin(credentialConfig, connectSocket) {
                            isCurrentConnectionGeneration(generation, connectSocket)
                        }
                    }
                }
            }
        } else {
            emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE
            clearTransientDeclinePushMode()
        }
    }

    /**
     * Connects to the socket by token and using this client as the listener
     * Will respond with 'No Network Connection' if there is no network available
     * @see [TxSocket]
     * @param providedServerConfig, the TxServerConfiguration used to connect to the socket
     * @param txPushMetaData, the push metadata used to connect to a call from push
     * (Get this from push notification - fcm data payload)
     * required fot push calls to work
     * @param tokenConfig, represents a SIP user for login - token based
     * @param autoLogin, if true, the SDK will automatically log in with
     * the provided credentials on connection established
     * We recommend setting this to true
     *
     */
    fun connect(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true,
    ) {
        clearTransientDeclinePushMode()
        applyCallReportV2Environment(providedServerConfig)
        isSocketDebug = tokenConfig.debug
        enableCallQualityReports = tokenConfig.enableCallQualityReports
        callQualityReportInterval = tokenConfig.callQualityReportInterval
        sendWebRTCStatsViaSocket = tokenConfig.sendWebRTCStatsViaSocket
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(tokenConfig.logLevel, tokenConfig.customLogger)

        providedHostAddress = if (txPushMetaData != null) {
            val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
            processCallFromPush(metadata, tokenConfig.pushWhenActive)
            providedServerConfig.host
        } else {
            providedServerConfig.host
        }

        if (tokenConfig.region != Region.AUTO) {
            v2RegionHint = tokenConfig.region.value
            providedHostAddress = "${tokenConfig.region.value}.$providedHostAddress"
        }

        val generation = nextConnectionGeneration()
        socket.destroy()
        val connectSocket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )
        // t027: every socket replacement participates in the login-commit ownership
        // guard so a response in flight can never bind across a half-swapped socket.
        synchronized(this) { socket = connectSocket }

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun
        if (ConnectivityHelper.isNetworkEnabled(context)) {
            v2Logger.d(message = "Provided Host Address: $providedHostAddress")

            launchSocketConnect {
                if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                    connectSocket.destroy()
                    return@launchSocketConnect
                }

                if (tokenConfig.fallbackOnRegionFailure) {
                    providedHostAddress = ConnectivityHelper.resolveReachableHost(
                        providedHostAddress!!,
                        providedPort!!
                    )
                    v2Logger.d(message = "Verified Host Address: $providedHostAddress")
                }

                if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                    connectSocket.destroy()
                    return@launchSocketConnect
                }

                if (txPushMetaData != null) {
                    val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                    voiceSDKID = metadata.voiceSdkId
                } else if (voiceSDKID != null) {
                    pushMetaData = PushMetaData(
                        callerName = "",
                        callerNumber = "",
                        callId = "",
                        voiceSdkId = voiceSDKID
                    )
                }
                connectSocket.connectWithConnectionGuard(
                    this@TelnyxClient,
                    providedHostAddress,
                    providedPort,
                    pushMetaData,
                    isCurrentConnection = { isCurrentConnectionGeneration(generation, connectSocket) }
                ) {
                    if (autoLogin && isCurrentConnectionGeneration(generation, connectSocket)) {
                        tokenLogin(tokenConfig, connectSocket) {
                            isCurrentConnectionGeneration(generation, connectSocket)
                        }
                    }
                }

            }
        } else {
            emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE
            clearTransientDeclinePushMode()
        }
    }

    /**
     * Connects to the socket for anonymous authentication (AI assistant connections).
     * This method allows connecting to AI assistants without traditional SIP credentials.
     *
     * @param providedServerConfig The server configuration for connection
     * @param targetId The unique identifier of the target AI assistant
     * @param targetType The type of target (defaults to "ai_assistant")
     * @param targetVersionId Optional version ID of the target
     * @param conversationId Optional conversation ID to join an existing conversation
     * @param userVariables Optional user variables to include
     * @param reconnection Whether this is a reconnection attempt (defaults to false)
     */
    fun connectAnonymously(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        conversationId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
        logLevel: LogLevel = LogLevel.NONE,
    ) {
        clearTransientDeclinePushMode()
        applyCallReportV2Environment(providedServerConfig)
        emitSocketResponse(SocketResponse.initialised())
        waitingForReg = true
        invalidateGatewayResponseTimer()
        resetGatewayCounters()

        setSDKLogLevel(logLevel, null)

        providedHostAddress = providedServerConfig.host

        val generation = nextConnectionGeneration()
        socket.destroy()
        val connectSocket = TxSocket(
            host_address = providedHostAddress!!,
            port = providedServerConfig.port
        )
        // t027: every socket replacement participates in the login-commit ownership
        // guard so a response in flight can never bind across a half-swapped socket.
        synchronized(this) { socket = connectSocket }

        providedPort = providedServerConfig.port
        providedTurn = providedServerConfig.turn
        providedStun = providedServerConfig.stun

        if (ConnectivityHelper.isNetworkEnabled(context)) {
            v2Logger.d(message = "Provided Host Address: $providedHostAddress")

            launchSocketConnect {
                if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                    connectSocket.destroy()
                    return@launchSocketConnect
                }

                connectSocket.connectWithConnectionGuard(
                    this@TelnyxClient,
                    providedHostAddress,
                    providedPort,
                    null,
                    isCurrentConnection = { isCurrentConnectionGeneration(generation, connectSocket) }
                ) socketConnect@ {
                    if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                        return@socketConnect
                    }

                    // Perform anonymous login after socket is connected
                    anonymousLogin(
                        targetId = targetId,
                        targetType = targetType,
                        targetVersionId = targetVersionId,
                        conversationId = conversationId,
                        userVariables = userVariables,
                        reconnection = reconnection,
                        targetSocket = connectSocket,
                        canSend = { isCurrentConnectionGeneration(generation, connectSocket) },
                    )
                }
            }
        } else {
            emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE
            clearTransientDeclinePushMode()
        }
    }

    /**
     * Connects to the socket with decline_push parameter for background call decline.
     * This method is specifically designed for declining calls without launching the main app.
     * Prefer [createDeclinePushClient] so decline work is isolated from the normal SDK client.
     * Calling this method on an existing client remains supported for compatibility.
     *
     * @param providedServerConfig The server configuration for connection
     * @param config The configuration for login (either CredentialConfig or TokenConfig)
     * @param txPushMetaData The push metadata from the notification
     */
    fun connectWithDeclinePush(
        providedServerConfig: TxServerConfiguration = TxServerConfiguration(),
        config: TelnyxConfig,
        txPushMetaData: String? = null,
    ) {
        isDeclinePushConnection = true
        applyCallReportV2Environment(providedServerConfig)
        val declineConfig = when (config) {
            is CredentialConfig -> config.copy(autoReconnect = false)
            is TokenConfig -> config.copy(autoReconnect = false)
        }
        try {
            emitSocketResponse(SocketResponse.initialised())
            waitingForReg = true
            invalidateGatewayResponseTimer()
            resetGatewayCounters()

            providedHostAddress = if (txPushMetaData != null) {
                val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                pushMetaData = metadata
                providedServerConfig.host
            } else {
                providedServerConfig.host
            }

            val generation = nextConnectionGeneration()
            socket.destroy()
            val connectSocket = TxSocket(
                host_address = providedHostAddress!!,
                port = providedServerConfig.port
            )
            // t027: every socket replacement participates in the login-commit ownership
        // guard so a response in flight can never bind across a half-swapped socket.
        synchronized(this) { socket = connectSocket }

            providedPort = providedServerConfig.port
            providedTurn = providedServerConfig.turn
            providedStun = providedServerConfig.stun

            if (ConnectivityHelper.isNetworkEnabled(context)) {
                v2Logger.d(message = "Provided Host Address: $providedHostAddress")
                if (txPushMetaData != null) {
                    val metadata = Gson().fromJson(txPushMetaData, PushMetaData::class.java)
                    voiceSDKID = metadata.voiceSdkId
                } else if (voiceSDKID != null) {
                    pushMetaData = PushMetaData(
                        callerName = "",
                        callerNumber = "",
                        callId = "",
                        voiceSdkId = voiceSDKID
                    )
                }
                launchSocketConnect {
                    connectSocket.connectWithConnectionGuard(
                        this@TelnyxClient,
                        providedHostAddress,
                        providedPort,
                        pushMetaData,
                        isCurrentConnection = { isCurrentConnectionGeneration(generation, connectSocket) }
                    ) socketConnect@ {
                        if (!isCurrentConnectionGeneration(generation, connectSocket)) {
                            return@socketConnect
                        }

                        when (declineConfig) {
                            is CredentialConfig -> {
                                setSDKLogLevel(declineConfig.logLevel, declineConfig.customLogger)
                                credentialLoginWithDeclinePush(declineConfig, connectSocket) {
                                    isCurrentConnectionGeneration(generation, connectSocket)
                                }
                            }

                            is TokenConfig -> {
                                setSDKLogLevel(declineConfig.logLevel, declineConfig.customLogger)
                                tokenLoginWithDeclinePush(declineConfig, connectSocket) {
                                    isCurrentConnectionGeneration(generation, connectSocket)
                                }
                            }
                        }
                    }
                }
            } else {
                emitError(TelnyxErrorCode.NETWORK_OFFLINE.code) // NETWORK_OFFLINE
                clearTransientDeclinePushMode()
            }
        } catch (e: Exception) {
            clearTransientDeclinePushMode()
            throw e
        }
    }


    /**
     * Sets the callOngoing state to true. This can be used to see if the SDK thinks a call is ongoing.
     */
    internal fun callOngoing() {
        socket.callOngoing()
    }

    /**
     * Sets the callOngoing state to false if the [calls] MutableMap is empty
     * @see [calls]
     */
    internal fun callNotOngoing() {
        if (calls.isEmpty()) {
            socket.callNotOngoing()
        }
    }

    /**
     * register network state change callback.
     * @see [ConnectivityManager]
     */
    private fun registerNetworkCallback() {
        context.let {
            ConnectivityHelper.registerNetworkStatusCallback(it, networkCallback)
            isNetworkCallbackRegistered = true
        }
    }

    /**
     * Unregister network state change callback.
     * @see [ConnectivityManager]
     */
    private fun unregisterNetworkCallback() {
        if (isNetworkCallbackRegistered) {
            context.let {
                ConnectivityHelper.unregisterNetworkStatusCallback(it, networkCallback)
                isNetworkCallbackRegistered = false
            }
        }
    }

    /**
     * Returns the socket response in the form of LiveData (deprecated)
     * The format of each message is provided in SocketResponse and ReceivedMessageBody
     * @see [SocketResponse]
     * @see [ReceivedMessageBody]
     * @deprecated Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use getSocketResponseFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    fun getSocketResponse(): LiveData<SocketResponse<ReceivedMessageBody>> = socketResponseLiveData

    /**
     * Returns the  json messages from socket in the form of LiveData used for debugging purposes
     * @deprecated Use wsMessageResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use wsMessagesResponseFlow instead. LiveData is deprecated in favor of Kotlin Flows.")
    fun getWsMessageResponse(): LiveData<JsonObject> = wsMessagesResponseLiveData

    /**
     * Returns all active calls that have been stored in our calls MutableMap
     * The MutableMap is converted into a Map - preventing any changes by the SDK User
     *
     * @see [calls]
     */
    fun getActiveCalls(): Map<UUID, Call> {
        return calls.values.associateBy { it.callId }
    }
    
    /**
     * Returns the current connection quality based on the latest metrics
     * @return Current [SocketConnectionQuality], or DISCONNECTED if no metrics available
     */
    fun getCurrentConnectionQuality(): SocketConnectionQuality {
        return currentSocketConnectionMetrics?.quality ?: SocketConnectionQuality.DISCONNECTED
    }
    
    /**
     * Returns the latest connection metrics
     * @return Current [SocketConnectionMetrics], or null if no metrics available yet
     */
    fun getConnectionMetrics(): SocketConnectionMetrics? {
        return currentSocketConnectionMetrics
    }

    /**
     * Logs the user in with credentials provided via CredentialConfig
     *
     * @param config, the CredentialConfig used to log in
     * @see [CredentialConfig]
     */
    @Deprecated("telnyxclient.credentialLogin is deprecated. Use telnyxclient.connect(..) instead.")
    fun credentialLogin(config: CredentialConfig) {
        credentialLogin(config, socket) { true }
    }

    private fun credentialLogin(
        config: CredentialConfig,
        targetSocket: TxSocket,
        canSend: () -> Boolean,
    ) {
        if (!canSend()) {
            return
        }

        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        credentialSessionConfig = config

        isSocketDebug = config.debug
        enableCallQualityReports = config.enableCallQualityReports
        callQualityReportInterval = config.callQualityReportInterval
        sendWebRTCStatsViaSocket = config.sendWebRTCStatsViaSocket
        useTrickleIce = config.useTrickleIce

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")
        if (config.pushWhenActive) {
            notificationJsonObject.addProperty("push_when_active", true)
            notificationJsonObject.addProperty("pn_late_fanout", true)
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                sdkInstanceId = callReportV2InstanceId,
                telemetryEnabled = callReportV2?.sendsToServerForLogin() == true,
                loginToken = null,
                login = user,
                passwd = password,
                userVariables = notificationJsonObject,
                loginParams = mapOf("attach_call" to "true"),
                sessid = sessid,
                mobileSdkName = sdkIdentity.name,
                mobileSdkVersion = sdkIdentity.version,
            )
        )
        v2Logger.d(message = "Auto login with credentialConfig")
        
        // Start registration latency tracking
        latencyTracker.startRegistrationTracking()
        latencyTracker.markRegistrationMilestone(LatencyTracker.MILESTONE_LOGIN_SENT)

        if (!canSend()) {
            return
        }
        // t027 B-WIN: publish/invalidate the auth owner (which also clears the
        // recorder session under the same guard) BEFORE any new capture auth/clear
        // effect, so an older response can never bind under an already-new auth.
        v2NotePendingLogin(uuid, targetSocket)
        callReportV2?.authenticate("sip_credentials", user, password)
        reportPendingPushAttachStarted(uuid)
        targetSocket.send(loginMessage)
    }


    /**
     * Disables push notifications for current logged in user.
     *
     * NB : Push Notifications are enabled by default after login
     *
     * returns : {"jsonrpc":"2.0","id":"","result":{"message":"disable push notification success"}}
     * */
    fun disablePushNotification() {
        val storedConfig = credentialSessionConfig ?: tokenSessionConfig ?: return

        val params = when (storedConfig) {
            is CredentialConfig -> {
                DisablePushParams(
                    user = storedConfig.sipUser,
                    userVariables = UserVariables(storedConfig.fcmToken ?: "")
                )
            }

            is TokenConfig -> {
                TokenDisablePushParams(
                    loginToken = storedConfig.sipToken,
                    userVariables = UserVariables(storedConfig.fcmToken ?: "")
                )
            }
        }

        val disablePushMessage = SendingMessageBody(
            id = UUID.randomUUID().toString(),
            method = SocketMethod.DISABLE_PUSH.methodName,
            params = params
        )
        val message = Gson().toJson(disablePushMessage)
        v2Logger.d("disablePushMessage", message)
        socket.send(disablePushMessage)
    }


    private var attachCallId: String? = null

    /**
     * Attaches push notifications to current call invite.
     * Backend responds with INVITE message
     * */
    private fun attachCall() {

        attachCallId = UUID.randomUUID().toString()
        reportPendingPushAttachStarted(attachCallId!!)
        val params = AttachCallParams(
            userVariables = AttachUserVariables(),
            mobileSdkName = sdkIdentity.name,
            mobileSdkVersion = sdkIdentity.version,
        )

        val attachPushMessage = SendingMessageBody(
            id = attachCallId!!,
            method = SocketMethod.ATTACH_CALL.methodName,
            params = params
        )
        v2Logger.d("sending attach Call", attachPushMessage.toString())
        socket.send(attachPushMessage)
        startPushInviteTimeout()
    }

    private fun startPushInviteTimeout() {
        pushInviteTimeoutJob?.cancel()
        // Read pending callId under the same lock used by claimPendingPushCall()
        // and clearPendingPushCall() to avoid a TOCTOU race where the pending
        // state is cleared between this read and the coroutine's claim.
        val pendingCallId = synchronized(this) {
            pushMetaData?.callId.toUuidOrNull() ?: pendingPushAppCallId
        }
        if (pendingCallId == null) {
            v2Logger.w(message = "Cannot start push INVITE timeout without a valid push call_id")
            return
        }

        v2Logger.d(message = "Starting post-attach INVITE timeout for push call $pendingCallId")
        pushInviteTimeoutJob = clientScope.launch {
            delay(pushInviteTimeoutMs)
            val claimedCallId = claimPendingPushCall() ?: return@launch
            reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED,
                claimedCallId, com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Reason.INVITE_TIMEOUT)

            val terminationReason = CallTerminationReason(
                cause = "ORIGINATOR_CANCEL",
                causeCode = 487,
                sipCode = 487,
                sipReason = "Request Terminated"
            )
            emitPendingPushBye(
                appCallId = claimedCallId,
                cause = terminationReason.cause,
                causeCode = terminationReason.causeCode,
                sipCode = terminationReason.sipCode,
                sipReason = terminationReason.sipReason
            )
        }
    }

    /** Atomically takes ownership of pending push cleanup for INVITE/BYE/timeout races. */
    private fun claimPendingPushCall(): UUID? = synchronized(this) {
        if (!isCallPendingFromPush) return@synchronized null
        val callId = pushMetaData?.callId.toUuidOrNull() ?: pendingPushAppCallId
        isCallPendingFromPush = false
        pendingPushAppCallId = null
        pushMetaData = null
        pushInviteTimeoutJob?.cancel()
        pushInviteTimeoutJob = null
        callId
    }

    private fun emitPendingPushBye(
        appCallId: UUID,
        cause: String?,
        causeCode: Int?,
        sipCode: Int?,
        sipReason: String?,
    ) {
        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.BYE.methodName,
                    ByeResponse(appCallId, cause, causeCode, sipCode, sipReason)
                )
            )
        )
    }


    /**
     * Logs the user in with credentials provided via TokenConfig
     *
     * @param config, the TokenConfig used to log in
     * @see [TokenConfig]
     */
    @Deprecated(
        "telnyxclient.tokenLogin is deprecated. Use telnyxclient.connect(...,autoLogin:true) " +
                "with autoLogin set to true instead."
    )
    fun tokenLogin(config: TokenConfig) {
        tokenLogin(config, socket) { true }
    }

    private fun tokenLogin(
        config: TokenConfig,
        targetSocket: TxSocket,
        canSend: () -> Boolean,
    ) {
        if (!canSend()) {
            return
        }

        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        tokenSessionConfig = config

        isSocketDebug = config.debug
        enableCallQualityReports = config.enableCallQualityReports
        callQualityReportInterval = config.callQualityReportInterval
        sendWebRTCStatsViaSocket = config.sendWebRTCStatsViaSocket
        useTrickleIce = config.useTrickleIce

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")
        if (config.pushWhenActive) {
            notificationJsonObject.addProperty("push_when_active", true)
            notificationJsonObject.addProperty("pn_late_fanout", true)
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                sdkInstanceId = callReportV2InstanceId,
                telemetryEnabled = callReportV2?.sendsToServerForLogin() == true,
                loginToken = token,
                login = null,
                passwd = null,
                userVariables = notificationJsonObject,
                loginParams = mapOf("attach_call" to "true"),
                sessid = sessid,
                mobileSdkName = sdkIdentity.name,
                mobileSdkVersion = sdkIdentity.version,
            )
        )
        
        // Start registration latency tracking
        latencyTracker.startRegistrationTracking()
        latencyTracker.markRegistrationMilestone(LatencyTracker.MILESTONE_LOGIN_SENT)
        
        if (!canSend()) {
            return
        }
        // t027 B-WIN: owner publication precedes the capture auth (see above).
        v2NotePendingLogin(uuid, targetSocket)
        callReportV2?.authenticate("token", null, token)
        reportPendingPushAttachStarted(uuid)
        targetSocket.send(loginMessage)
    }

    /**
     * Performs anonymous login for AI assistant connections.
     * This method allows connecting to AI assistants without traditional SIP credentials.
     *
     * @param targetId the unique identifier of the target AI assistant
     * @param targetType the type of target (defaults to "ai_assistant")
     * @param targetVersionId optional version ID of the target
     * @param conversationId optional conversation ID to join an existing conversation
     * @param userVariables optional user variables to include
     * @param reconnection whether this is a reconnection attempt (defaults to false)
     */
    fun anonymousLogin(
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        conversationId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
    ) {
        anonymousLogin(
            targetId = targetId,
            targetType = targetType,
            targetVersionId = targetVersionId,
            conversationId = conversationId,
            userVariables = userVariables,
            reconnection = reconnection,
            targetSocket = socket,
            canSend = { true },
        )
    }

    private fun anonymousLogin(
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        conversationId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
        targetSocket: TxSocket,
        canSend: () -> Boolean,
    ) {
        if (!canSend()) {
            return
        }

        val uuid: String = UUID.randomUUID().toString()

        val userAgent = UserAgent(
            sdkVersion = SDK_VERSION,
            data = "Android-$SDK_VERSION"
        )

        val anonymousLoginParams = AnonymousLoginParams(
            sdkInstanceId = callReportV2InstanceId,
            telemetryEnabled = false,
            targetType = targetType,
            targetId = targetId,
            targetVersionId = targetVersionId,
            conversationId = conversationId,
            userVariables = userVariables,
            reconnection = reconnection,
            userAgent = userAgent,
            mobileSdkName = sdkIdentity.name,
            mobileSdkVersion = sdkIdentity.version,
            sessid = sessid
        )

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.ANONYMOUS_LOGIN.methodName,
            params = anonymousLoginParams
        )

        v2Logger.d(message = "Anonymous Login Message: ${Gson().toJson(loginMessage)}")
        if (!canSend()) {
            return
        }
        // t027 B-WIN: owner publication precedes the capture auth (see above).
        v2NotePendingLogin(uuid, targetSocket)
        callReportV2?.authenticate("anonymous", targetId, targetType)
        targetSocket.send(loginMessage)
    }

    fun sendAIAssistantMessage(
        message: String,
        imagesUrls: List<String>? = null
    ) {
        val uuid: String = UUID.randomUUID().toString()

        val conversationContent = mutableListOf<ConversationContent>()
        conversationContent.add(ConversationContent(
            type = "input_text",
            text = message
        ))

        imagesUrls?.forEach { imageUrl ->
            val conversationImageURL = ConversationImageURL(imageUrl)
            conversationContent.add(ConversationContent(
                type = "image_url",
                imageUrl = conversationImageURL
            ))
        }

        val conversationItem = ConversationItem(
            id = UUID.randomUUID().toString(),
            type = "message",
            role = "user",
            content = conversationContent
        )

        val aiConversationParams = AiConversationParams(
            type = "conversation.item.create",
            item = conversationItem
        )

        val aiConversationMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.AI_CONVERSATION.methodName,
            params = aiConversationParams
        )

        v2Logger.d(message = "AI Conversation Message: ${Gson().toJson(aiConversationMessage)}")
        socket.send(aiConversationMessage)
    }

    /**
     * Sends an updateMedia modify message for ICE renegotiation
     * @param callId the UUID of the call to renegotiate
     * @param sdp the new local SDP for ICE restart
     */
    fun sendUpdateMediaMessage(callId: UUID, sdp: String) {
        sendUpdateMediaMessage(callId, sdp, UUID.randomUUID().toString(), null)
    }

    /** Sends updateMedia with explicit trickle ICE signaling semantics. */
    internal fun sendUpdateMediaMessage(callId: UUID, sdp: String, trickle: Boolean?) {
        sendUpdateMediaMessage(callId, sdp, UUID.randomUUID().toString(), trickle)
    }

    /** Sends a monitor-owned updateMedia request with a retained id. */
    internal fun sendRecoveryUpdateMediaMessage(
        callId: UUID,
        sdp: String,
        requestId: String,
    ) {
        sendUpdateMediaMessage(callId, sdp, requestId, null)
    }

    /** Sends a monitor-owned updateMedia request with explicit trickle ICE semantics. */
    internal fun sendRecoveryUpdateMediaMessage(
        callId: UUID,
        sdp: String,
        requestId: String,
        trickle: Boolean?,
    ) {
        sendUpdateMediaMessage(callId, sdp, requestId, trickle)
    }

    private fun sendUpdateMediaMessage(
        callId: UUID,
        sdp: String,
        requestId: String,
        trickle: Boolean?,
    ) {

        val callDialogParams = CallDialogParams(
            callId = callId,
            customHeaders = arrayListOf()
        )

        val modifyParams = ModifyParams(
            sessid = sessid,
            action = "updateMedia",
            dialogParams = callDialogParams,
            sdp = sdp,
            trickle = trickle,
        )

        val modifyMessage = SendingMessageBody(
            id = requestId,
            method = SocketMethod.MODIFY.methodName,
            params = modifyParams
        )

        v2Logger.d(message = "Update Media Message: ${Gson().toJson(modifyMessage)}")
        socket.send(modifyMessage)
    }

    /**
     * Sends the same authenticated JSON-RPC signaling probe used by iOS.
     * The caller-generated id is preserved so only its matching result/error
     * can authorize an ICE restart.
     */
    internal fun sendRecoveryProbe(probeId: String): Boolean {
        if (!socket.isConnected) return false

        val probe = JsonObject().apply {
            addProperty("id", probeId)
            addProperty("jsonrpc", "2.0")
            addProperty("method", SocketMethod.PINGPONG.methodName)
            add("params", JsonObject())
            voiceSDKID?.let { addProperty("voice_sdk_id", it) }
        }
        socket.send(probe)
        return true
    }

    /**
     * Performs credential login with decline_push parameter for background call decline.
     * This method sends a login message with decline_push set to true.
     *
     * @param config The credential configuration for login
     */
    private fun credentialLoginWithDeclinePush(
        config: CredentialConfig,
        targetSocket: TxSocket = socket,
        canSend: () -> Boolean = { true },
    ) {
        if (!canSend()) {
            return
        }

        val uuid: String = UUID.randomUUID().toString()
        val user = config.sipUser
        val password = config.sipPassword
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        credentialSessionConfig = config

        isSocketDebug = config.debug
        enableCallQualityReports = config.enableCallQualityReports
        callQualityReportInterval = config.callQualityReportInterval
        sendWebRTCStatsViaSocket = config.sendWebRTCStatsViaSocket
        useTrickleIce = config.useTrickleIce

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")
        if (config.pushWhenActive) {
            notificationJsonObject.addProperty("push_when_active", true)
            notificationJsonObject.addProperty("pn_late_fanout", true)
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                sdkInstanceId = callReportV2InstanceId,
                telemetryEnabled = callReportV2?.sendsToServerForLogin() == true,
                loginToken = null,
                login = user,
                passwd = password,
                userVariables = notificationJsonObject,
                loginParams = mapOf("decline_push" to "true"),
                sessid = sessid
            )
        )
        v2Logger.d(message = "Auto login with credentialConfig for decline push")

        if (!canSend()) {
            return
        }
        // t027 B-WIN: owner publication precedes the capture auth (see above).
        v2NotePendingLogin(uuid, targetSocket)
        callReportV2?.authenticate("sip_credentials", user, password)
        targetSocket.send(loginMessage)
    }

    /**
     * Performs token login with decline_push parameter for background call decline.
     * This method sends a login message with decline_push set to true.
     *
     * @param config The token configuration for login
     */
    private fun tokenLoginWithDeclinePush(
        config: TokenConfig,
        targetSocket: TxSocket = socket,
        canSend: () -> Boolean = { true },
    ) {
        if (!canSend()) {
            return
        }

        val uuid: String = UUID.randomUUID().toString()
        val token = config.sipToken
        val fcmToken = config.fcmToken
        val logLevel = config.logLevel
        val customLogger = config.customLogger
        autoReconnectLogin = config.autoReconnect

        tokenSessionConfig = config

        isSocketDebug = config.debug
        enableCallQualityReports = config.enableCallQualityReports
        callQualityReportInterval = config.callQualityReportInterval
        sendWebRTCStatsViaSocket = config.sendWebRTCStatsViaSocket
        useTrickleIce = config.useTrickleIce

        setSDKLogLevel(logLevel, customLogger)

        config.ringtone?.let {
            rawRingtone = it
        }
        config.ringBackTone?.let {
            rawRingbackTone = it
        }

        var firebaseToken = ""
        if (fcmToken != null) {
            firebaseToken = fcmToken
        }

        val notificationJsonObject = JsonObject()
        notificationJsonObject.addProperty("push_device_token", firebaseToken)
        notificationJsonObject.addProperty("push_notification_provider", "android")
        if (config.pushWhenActive) {
            notificationJsonObject.addProperty("push_when_active", true)
            notificationJsonObject.addProperty("pn_late_fanout", true)
        }

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.LOGIN.methodName,
            params = LoginParam(
                sdkInstanceId = callReportV2InstanceId,
                telemetryEnabled = callReportV2?.sendsToServerForLogin() == true,
                loginToken = token,
                login = null,
                passwd = null,
                userVariables = notificationJsonObject,
                loginParams = mapOf("decline_push" to "true"),
                sessid = sessid
            )
        )
        v2Logger.d(message = "Auto login with tokenConfig for decline push")

        if (!canSend()) {
            return
        }
        // t027 B-WIN: owner publication precedes the capture auth (see above).
        v2NotePendingLogin(uuid, targetSocket)
        callReportV2?.authenticate("token", null, token)
        targetSocket.send(loginMessage)
    }


    /**
     * Sets the global SDK log level
     * Logging is implemented with the Logger provided via the [Config],
     * if none is provided then the default logger in [TxLogger] is used
     *
     * @param logLevel The LogLevel specified for the SDK
     * @param customLogger Optional custom logger implementation
     * @see [LogLevel]
     * @see [TxLogger]
     */
    private fun setSDKLogLevel(logLevel: LogLevel, customLogger: TxLogger? = null) {
        v2Logger.init(logLevel, customLogger)
    }

    /**
     * Returns a MutableList of available audio devices
     * Audio devices are represented by their Int reference ids
     *
     * @return [MutableList] of [Int]
     */
    private fun getAvailableAudioOutputTypes(): MutableList<Int> {
        val availableTypes: MutableList<Int> = mutableListOf()
        audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.forEach {
            availableTypes.add(it.type)
        }
        return availableTypes
    }

    /**
     * Sets the audio device that the SDK should use
     *
     * @param audioDevice, the chosen [AudioDevice] to be used by the SDK
     * @see [AudioDevice]
     */
    fun setAudioOutputDevice(audioDevice: AudioDevice) {
        callReportV2?.nativeFact(com.telnyx.webrtc.sdk.observability.V2NativeFact.AudioOutputRequested(audioDevice))
        val availableTypes = getAvailableAudioOutputTypes()
        when (audioDevice) {
            AudioDevice.BLUETOOTH -> {
                if (availableTypes.contains(AudioDevice.BLUETOOTH.code)) {
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager?.isBluetoothScoOn = true
                } else {
                    v2Logger.d(
                        message = v2Logger.formatMessage(
                            "[%s] :: No Bluetooth device detected",
                            this@TelnyxClient.javaClass.simpleName
                        )
                    )
                }
            }

            AudioDevice.PHONE_EARPIECE -> {
                // For phone ear piece
                audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                audioManager?.isBluetoothScoOn = false
                audioManager?.isSpeakerphoneOn = false
            }

            AudioDevice.LOUDSPEAKER -> {
                // For phone speaker(loudspeaker)
                audioManager?.mode = AudioManager.MODE_NORMAL
                audioManager?.isBluetoothScoOn = false
                audioManager?.isSpeakerphoneOn = true
            }
        }
    }


    /**
     * Use MediaPlayer to play the audio of the saved user Ringtone
     * If no ringtone was provided, we print a relevant message
     *
     * @see [MediaPlayer]
     */
    internal fun playRingtone() {
        // set speakerState to current audioManager settings
        speakerState = if (speakerState != UNASSIGNED) {
            if (audioManager?.isSpeakerphoneOn == true) {
                SPEAKER
            } else {
                EARPIECE
            }
        } else {
            EARPIECE
        }

        // set audioManager to ringtone settings
        audioManager?.mode = AudioManager.MODE_RINGTONE
        audioManager?.isSpeakerphoneOn = true

        rawRingtone?.let {
            stopMediaPlayer()
            try {

                if (it.getRingtoneType() == URI) {
                    mediaPlayer = MediaPlayer.create(context, it as Uri)
                } else if (it.getRingtoneType() == RAW) {
                    mediaPlayer = MediaPlayer.create(context, it as Int)
                }
                val player = mediaPlayer ?: kotlin.run {
                    v2Logger.d(message = "Ringtone not valid:: No ringtone will be played")
                    return
                }
                player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                if (!player.isPlaying) {
                    player.start()
                    player.isLooping = true
                }
                v2Logger.d(message = "Ringtone playing")
            } catch (e: TypeCastException) {
                v2Logger.e(message = "Exception: ${e.message}")
            } catch (e: RuntimeException) {
                releaseMediaPlayerAfterPlaybackFailure(e)
            }
        } ?: run {
            v2Logger.d(message = "No ringtone specified :: No ringtone will be played")
        }
    }

    private fun setSpeakerMode(speakerMode: SpeakerMode) {
        when (speakerMode) {
            SPEAKER -> {
                audioManager?.isSpeakerphoneOn = true
            }

            EARPIECE -> {
                audioManager?.isSpeakerphoneOn = false
            }

            UNASSIGNED -> audioManager?.isSpeakerphoneOn = false
        }
    }

    private fun Any?.getRingtoneType(): RingtoneType? {
        return when (this) {
            is Uri -> URI
            is Int -> RAW
            else -> null
        }
    }

    /**
     * Use MediaPlayer to play the audio of the saved user Ringback tone
     * If no ringback tone was provided, we print a relevant message
     *
     * @see [MediaPlayer]
     */
    private fun playRingBackTone() {
        rawRingbackTone?.let {
            stopMediaPlayer()
            try {
                mediaPlayer = MediaPlayer.create(context, it)
                val player = mediaPlayer ?: kotlin.run {
                    v2Logger.d(message = "Ringback tone not valid:: No ringback tone will be played")
                    return
                }
                player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
                if (!player.isPlaying) {
                    player.start()
                    player.isLooping = true
                }
            } catch (e: RuntimeException) {
                releaseMediaPlayerAfterPlaybackFailure(e)
            }
        } ?: run {
            v2Logger.d(message = "No ringtone specified :: No ringtone will be played")
        }
    }

    private fun releaseMediaPlayerAfterPlaybackFailure(exception: Exception) {
        v2Logger.e(message = "Exception: ${exception.message}")
        mediaPlayer?.release()
        mediaPlayer = null
    }

    /**
     * Stops any audio that the MediaPlayer is playing
     * @see [MediaPlayer]
     */
    private fun stopMediaPlayer() {
        if (mediaPlayer != null) {
            mediaPlayer!!.stop()
            mediaPlayer!!.reset()
            mediaPlayer!!.release()
            mediaPlayer = null
        }
        v2Logger.d(message = "ringtone/ringback media player stopped and released")

        // reset audio mode to communication
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun requestGatewayStatus() {
        if (waitingForReg) {
            val gatewayRpcId = UUID.randomUUID().toString()
            // t027 B-GW: publish/invalidate the gateway-RPC owner under the same
            // guard as the login commit, BEFORE the RPC is sent, so a stale REGED
            // response can never complete a newer auth's registration.
            synchronized(this) {
                v2LoginAttemptEpoch += 1
                v2PendingGateway = PendingGatewayState(gatewayRpcId, socket, v2LoginAttemptEpoch, connectionGeneration.get())
            }
            socket.send(
                SendingMessageBody(
                    id = gatewayRpcId,
                    method = SocketMethod.GATEWAY_STATE.methodName,
                    params = StateParams(
                        state = null
                    )
                )
            )
        }
    }

    /** Current outstanding gateway-RPC publication, guarded like the login commit. */
    private data class PendingGatewayState(val rpcId: String, val socket: TxSocket, val epoch: Long, val generation: Int)

    @Volatile private var v2PendingGateway: PendingGatewayState? = null

    /**
     * t027 B-GW: internal Native-client dispatch seam for the correlated gateway
     * RPC response. The public TxSocketListener ABI is unchanged; a REGED state
     * can complete the registration only when it correlates with the CURRENT
     * outstanding gateway RPC (exact rpcId + socket + auth epoch + connection
     * generation). Uncorrelated states remain observations.
     */
    internal fun onGatewayStateResponseConfirmed(
        sentOn: TxSocket,
        responseId: String?,
        gatewayState: String,
        sessionId: String?,
    ) {
        val correlated = synchronized(this) {
            val published = v2PendingGateway
            val ok = published != null && responseId == published.rpcId &&
                    published.socket === sentOn && socket === sentOn &&
                    published.epoch == v2LoginAttemptEpoch &&
                    published.generation == connectionGeneration.get()
            if (ok) v2PendingGateway = null
            ok
        }
        processGatewayState(gatewayState, sessionId, regedCorrelated = correlated)
    }

    override fun onGatewayStateReceived(gatewayState: String, receivedSessionId: String?) {
        // Uncorrelated gateway states (server pushes) are observations: a pushed
        // REGED can never complete a registration (no outstanding correlated RPC).
        processGatewayState(gatewayState, receivedSessionId, regedCorrelated = false)
    }

    private fun processGatewayState(gatewayState: String, sessionId: String?, regedCorrelated: Boolean) {
        callReportV2?.gateway(gatewayState)
        when (gatewayState) {
            GatewayState.REGED.state -> {
                if (!regedCorrelated) return  // uncorrelated observation — never completes a registration
                invalidateGatewayResponseTimer()
                waitingForReg = false
                sessionId?.let {
                    resetGatewayCounters()
                    onLoginSuccessful(it)
                } ?: kotlin.run {
                    resetGatewayCounters()
                    onLoginSuccessful(sessid, providerConfirmed = false)
                }
            }

            GatewayState.NOREG.state -> {
                invalidateGatewayResponseTimer()
                emitError(TelnyxErrorCode.GATEWAY_FAILED.code) // GATEWAY_FAILED
                disconnectTransientDeclinePushConnection()
            }

            GatewayState.FAILED.state -> {
                invalidateGatewayResponseTimer()
                emitError(TelnyxErrorCode.GATEWAY_FAILED.code) // GATEWAY_FAILED
                disconnectTransientDeclinePushConnection()
            }

            (GatewayState.FAIL_WAIT.state), (GatewayState.DOWN.state) -> {
                if (!isDeclinePushConnection && autoReconnectLogin && connectRetryCounter < RETRY_CONNECT_TIME) {
                    connectRetryCounter++
                    v2Logger.d(
                        message = v2Logger.formatMessage(
                            "[%s] :: Attempting reconnection :: attempt $connectRetryCounter / $RETRY_CONNECT_TIME",
                            this@TelnyxClient.javaClass.simpleName
                        )
                    )
                    launchReconnectJob()
                } else {
                    invalidateGatewayResponseTimer()
                    emitError(TelnyxErrorCode.GATEWAY_FAILED.code) // GATEWAY_FAILED
                    disconnectTransientDeclinePushConnection()
                }
            }

            GatewayState.EXPIRED.state -> {
                invalidateGatewayResponseTimer()
                emitError(TelnyxErrorCode.GATEWAY_FAILED.code) // GATEWAY_FAILED
                disconnectTransientDeclinePushConnection()
            }

            GatewayState.UNREGED.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.TRYING.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.REGISTER.state -> {
                // NOOP - logged within TxSocket
            }

            GatewayState.UNREGISTER.state -> {
                // NOOP - logged within TxSocket
            }

            else -> {
                invalidateGatewayResponseTimer()
                emitError(TelnyxErrorCode.UNEXPECTED_ERROR.code) // UNEXPECTED_ERROR
            }
        }
    }

    /**
     * t027: Fires when a login success response arrives on a socket where the
     * login is not yet marked complete. [sentOn] is the socket that received the
     * response; [responseId] is the JSON-RPC id; [response] is the full frame.
     *
     * The response is honored only when it correlates with the LATEST outstanding
     * LOGIN RPC on this same live socket, and that decision is made as a single
     * linearizable owned transition: validation and every side effect
     * (session identity, recorder context, isLoggedIn, reconnect resolution and
     * timer cancellation) are executed under one guard, so a newer same-socket
     * auth (or a socket replacement) that publishes after this response was read
     * but before the commit wins and the stale response binds nothing. Stale
     * ownership is rejected — never corrected later by an eventual newer bind.
     *
     * Untrusted data parsing happens before the guard; the guard is never held
     * across network/suspend/blocking work (the capture publication is a
     * non-blocking queue submission).
     */
    internal fun onLoginResponseConfirmed(sentOn: TxSocket, responseId: String?, response: JsonObject) {
        onLoginResponseConfirmedImpl(sentOn, responseId, response, commitBarrier = {})
    }

    /**
     * Linearizable owned login-confirmation transition. [commitBarrier] runs
     * inside the owned guard after the response has passed the socket dispatch
     * checks and BEFORE any session/capture/isLoggedIn/timer side effect; it is
     * a no-op in production and is the deterministic (after-only) invariant test
     * barrier. The functional fail-before of the snapshot-vs-apply race uses the
     * mocked capture log seam instead (see TelnyxClientReconnectLifecycleTest).
     */
    internal fun onLoginResponseConfirmedImpl(
        sentOn: TxSocket,
        responseId: String?,
        response: JsonObject,
        commitBarrier: () -> Unit,
    ) {
        // Parse untrusted data outside the guard; failure here binds nothing.
        val confirmedSessionId = response?.getAsJsonObject("result")?.get("sessid")
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString?.takeIf { it.isNotBlank() } ?: return
        synchronized(this) {
            // Owned guard section: publication, validation and commit are
            // serialized. A newer auth publishing here (same socket or socket
            // replacement) supersedes this response before any side effect.
            commitBarrier()
            val published = v2PendingLogin ?: return
            if (responseId == null || responseId != published.rpcId) return  // stale/unknown id
            if (published.socket !== sentOn) return                          // superseded socket
            if (socket !== sentOn) return                                    // live socket liveness
            if (published.epoch != v2LoginAttemptEpoch) return               // superseded auth attempt
            if (published.generation != connectionGeneration.get()) return   // superseded connection
            if (response.has("error")) return                                // failed login
            // --- commit: all side effects under the same guard ---
            sessid = confirmedSessionId
            callReportV2ServerSessionId = confirmedSessionId
            socket.isLoggedIn = true
            // The recorder context is stamped with the connection generation and the
            // locally parsed/validated session of THIS correlated response — never a
            // re-read of a mutable field that the auth entrypoints may null.
            callReportV2?.signaling(published.generation.toLong(), voiceSDKID, confirmedSessionId)
            if (reconnecting) {
                // Resolve the reconnection state machine on a confirmed session: the
                // 60s timer must not outlive a successful relogin (t027 ROOT_CAUSE.md B).
                clearReconnectingStateUnderGuard()
            }
            if (v2PendingLogin === published) v2PendingLogin = null
        }
        // Logging is pure observation; it stays outside the guard so test seams can
        // never hold the commit monitor. Publication ordering is unaffected: the
        // recorder update above was enqueued inside the guard.
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onLoginResponseConfirmed :: [%s] :: session bound from correlated login response",
                this@TelnyxClient.javaClass.simpleName,
                confirmedSessionId
            )
        )
    }

    /** Latest outstanding LOGIN publication; only its exact correlated response may bind. */
    private data class PendingLogin(val rpcId: String, val socket: TxSocket, val epoch: Long, val generation: Int)

    /**
     * Records the latest outstanding LOGIN RPC and clears the recorder session in
     * the same guarded section. t027 B-WIN: publication/invalidation of the auth
     * owner is atomic with the commit guard and PRECEDES any new capture
     * auth/clear effect, so an older response can never bind under an
     * already-new capture auth (its rpcId no longer matches the publication).
     */
    private fun v2NotePendingLogin(rpcId: String, sentOn: TxSocket) {
        synchronized(this) {
            v2LoginAttemptEpoch += 1
            v2PendingLogin = PendingLogin(rpcId, sentOn, v2LoginAttemptEpoch, connectionGeneration.get())
            callReportV2ServerSessionId = null
        }
    }

    /**
     * Fires once we have successfully received a 'REGED' gateway response, meaning login was successful
     * @param receivedLoginSessionId, the session ID of the successfully registered session.
     */
    internal fun onLoginSuccessful(receivedLoginSessionId: String, providerConfirmed: Boolean = true) {
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onLoginSuccessful :: [%s] :: Ready to make calls",
                this@TelnyxClient.javaClass.simpleName,
                receivedLoginSessionId
            )
        )
        sessid = receivedLoginSessionId
        // JS parity (VSDK-393): re-check token expiry after every successful
        // login — a refreshed token re-arms the TOKEN_EXPIRING_SOON timer.
        checkTokenExpiry()
        if (providerConfirmed) callReportV2ServerSessionId = receivedLoginSessionId
        callReportV2?.signaling(connectionGeneration.get().toLong(), voiceSDKID, callReportV2ServerSessionId)
        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.LOGIN.methodName,
                    LoginResponse(receivedLoginSessionId)
                )
            )
        )

        socket.isLoggedIn = true

        if (reconnecting) {
            // t027: resolve the reconnection state machine on a confirmed
            // registration, regardless of active calls. The previous
            // `getActiveCalls().isEmpty()` gate left the 60s timer armed while an
            // answered call recovered, and the stale expiry killed the live call
            // with RECONNECTION_EXHAUSTED (45003) even though media flowed.
            clearReconnectingState()
        }

        if (isDeclinePushConnection && !isDedicatedDeclinePushClient) {
            disconnect()
            return
        }

        v2Logger.d(message = "isCallPendingFromPush $isCallPendingFromPush")
        //if there is a call pending from push, attach it
        if (!isDeclinePushConnection && isCallPendingFromPush) {
            attachCall()
        }
        
        // Complete registration latency tracking
        latencyTracker.completeRegistrationTracking()

        CoroutineScope(Dispatchers.Main).launch {
            emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.CLIENT_READY.methodName,
                        null
                    )
                )
            )

            callReportV2?.ready()
            emitConnectionStatus(ConnectionStatus.CLIENT_READY)
        }
    }

    // TxSocketListener Overrides
    override fun onClientReady(jsonObject: JsonObject) {
        val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
        if (voiceSdkID != null) {
            v2Logger.d(message = "Voice SDK ID _ $voiceSdkID")
            this@TelnyxClient.voiceSDKID = voiceSdkID
            callReportV2?.signaling(connectionGeneration.get().toLong(), voiceSDKID, callReportV2ServerSessionId)
            updateDebugDataCollectorUploadConfig()
        } else {
            v2Logger.e(message = "No Voice SDK ID")
        }

        if (gatewayState != GatewayState.REGED.state) {
            if (waitingForReg) {
                v2Logger.d(
                    message = v2Logger.formatMessage(
                        "[%s] :: onClientReady :: retrieving gateway state",
                        this@TelnyxClient.javaClass.simpleName
                    )
                )
                requestGatewayStatus()
                gatewayResponseTimer = Timer()
                gatewayResponseTimer?.schedule(
                    timerTask {
                        if (registrationRetryCounter < RETRY_REGISTER_TIME) {
                            if (waitingForReg) {
                                onClientReady(jsonObject)
                            }
                            registrationRetryCounter++
                        } else {
                            v2Logger.d(
                                message = v2Logger.formatMessage(
                                    "[%s] :: Gateway registration has timed out",
                                    this@TelnyxClient.javaClass.simpleName
                                )
                            )
                            emitError(TelnyxErrorCode.GATEWAY_FAILED.code) // GATEWAY_FAILED
                            disconnectTransientDeclinePushConnection()
                        }
                    },
                    GATEWAY_RESPONSE_DELAY
                )
            } else if (socket.isLoggedIn) {
                // t027: reconnect path. The session was confirmed by the login
                // response (onLoginConfirmed) and the server pushed client_ready
                // immediately after; no gateway re-poll is required (canonical:
                // the gateway state poll happens after the first login only).
                // This is where client_ready/session_timings are emitted for a
                // successful reconnect, restoring the metrics-acceptance context.
                onClientReadySessionConfirmed()
            }
        } else {
            onClientReadySessionConfirmed()
        }
    }

    /** Shared completion: the session is ready and the recorder context is bound. */
    private fun onClientReadySessionConfirmed() {
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onClientReady :: Ready to make calls",
                this@TelnyxClient.javaClass.simpleName
            )
        )

        // Complete registration latency tracking
        latencyTracker.completeRegistrationTracking()

        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.CLIENT_READY.methodName,
                    null
                )
            )
        )

        callReportV2?.ready()
        emitConnectionStatus(ConnectionStatus.CLIENT_READY)
    }

    private fun invalidateGatewayResponseTimer() {
        gatewayResponseTimer?.cancel()
        gatewayResponseTimer?.purge()
        gatewayResponseTimer = null
    }

    private fun resetGatewayCounters() {
        registrationRetryCounter = 0
        connectRetryCounter = 0
    }

    /** t027: voice_sdk_id the client would resume with on the current socket (may be null). */
    internal fun v2ResumeVoiceSdkId(): String? = voiceSDKID

    /** t027: client-level reconnect state at an observation point (socket close). */
    internal fun v2ReconnectingNow(): Boolean = reconnecting

    /** t027: the SIP identity of the logged-in session, when available (null for token/anonymous). */
    internal fun v2LocalSipIdentity(): String? = credentialSessionConfig?.sipUser

    /**
     * Resolves the reconnection state machine once the session is registered
     * again (REGED). The 60s reconnection timer must not outlive a successful
     * relogin: a stale expiry tears down healthy, answered calls
     * (t027 ROOT_CAUSE.md, B). Call-level recovery continues independently via
     * the ATTACH push; [onAttachReceived] also clears the reconnection state.
     */
    internal fun clearReconnectingState() {
        synchronized(this) { clearReconnectingStateUnderGuard() }
    }

    /**
     * Guard-local variant: callers already holding the login-commit monitor
     * ([TelnyxClient] itself) use this so the reconnect resolution and the
     * reconnection-timer ownership are serialized with auth publication and the
     * socket swap under one guard. Assumes the guard is held; never performs
     * blocking or network work.
     */
    private fun clearReconnectingStateUnderGuard() {
        reconnecting = false
        reconnectionRetryCounter.set(0)
        cancelReconnectionTimerUnderGuard()
    }

    private fun launchSocketConnect(block: suspend CoroutineScope.() -> Unit) {
        cancelSocketConnectJob()
        val job = clientScope.launch(Dispatchers.IO, block = block)
        socketConnectJob = job
        job.invokeOnCompletion {
            if (socketConnectJob == job) {
                socketConnectJob = null
            }
        }
    }

    private fun cancelSocketConnectJob() {
        socketConnectJob?.cancel()
        socketConnectJob = null
    }

    private fun launchAcceptCallJob(callId: UUID, block: suspend CoroutineScope.() -> Unit) {
        cancelAcceptCallJob(callId)
        val job = clientScope.launch(Dispatchers.IO, block = block)
        acceptCallJobs[callId] = job
        job.invokeOnCompletion {
            acceptCallJobs.remove(callId, job)
        }
    }

    private fun cancelAcceptCallJob(callId: UUID) {
        acceptCallJobs.remove(callId)?.cancel()
    }

    private fun cancelAcceptCallJobs() {
        acceptCallJobs.values.forEach { job ->
            job.cancel()
        }
        acceptCallJobs.clear()
    }

    /**
     * Starts the reconnection timer to track reconnection attempts.
     * If reconnection takes longer than RECONNECT_TIMEOUT, it will trigger an error.
     *
     * t027: the timer's job assignment is owned by the login-commit guard so
     * auth publication, the socket swap and timer ownership are serialized:
     * a reconnect confirmation can never cancel a newer cycle's timer.
     */
    private fun startReconnectionTimer() {
        v2Logger.d(message = "Starting reconnection timer")
        synchronized(this) {
            // Cancel any existing timer
            reconnectTimeOutJob?.cancel()
            reconnectTimeOutJob = null
            v2TimerEpoch += 1
            val epoch = v2TimerEpoch
            // Create a new timer to check for timeout
            var job: Job? = null
            job = CoroutineScope(Dispatchers.Default).launch {
                delay(
                    credentialSessionConfig?.reconnectionTimeout
                        ?: tokenSessionConfig?.reconnectionTimeout ?: RECONNECT_TIMEOUT
                )
                // Guard-local expiry decision: fire only if this exact job still owns
                // the reconnection timer with its own epoch and reconnecting is still
                // owned. A superseded job never cancels or clears a newer timer.
                val mayFire = synchronized(this@TelnyxClient) {
                    reconnectTimeOutJob === job && v2TimerEpoch == epoch && reconnecting
                }
                if (mayFire) {
                    // Deliver through the owned revalidation: the actual teardown
                    // action re-checks current ownership so a successful login or a
                    // newer cycle between decision and action is never killed.
                    Handler(Looper.getMainLooper()).post { fireReconnectionTimeoutIfOwned(job, epoch) }
                }
            }
            reconnectTimeOutJob = job
        }
    }

    /**
     * Actual delayed teardown action of the reconnection timer. Ownership is
     * revalidated under the guard at execution time: a successful login or a
     * newer cycle that replaced the timer between the expiry decision and this
     * run must not be killed, and an old job must never clear a newer timer.
     */
    internal fun fireReconnectionTimeoutIfOwned(job: Job?, epoch: Long) {
        // One atomic owned commit under the shared monitor: ownership validation,
        // the expiry claim, the victim capture (call refs + their owned reporter
        // refs) and their irrevocable detach, serialized with auth publication,
        // the socket swap and newer-cycle timer arming. No monitor is held across
        // network/suspend/blocking work.
        val victims: List<Call>
        val victimReporters: List<WebRTCReporter?>
        synchronized(this) {
            val ownedNow = reconnectTimeOutJob === job && v2TimerEpoch == epoch && reconnecting
            if (!ownedNow) return
            reconnecting = false
            reconnectionRetryCounter.set(0)
            if (reconnectTimeOutJob === job && v2TimerEpoch == epoch) reconnectTimeOutJob = null
            victims = calls.values.toList()
            victimReporters = victims.map { call ->
                call.currentSignalingCallId().let { signalingCallId ->
                    // capture/detach the owned reporter ref now: no late lookup can
                    // match a NEW reporter registered by a reused signaling ID.
                    webRTCReportersMap.remove(signalingCallId)
                }
            }
            victims.forEach { call -> calls.remove(call.currentSignalingCallId()) }
        }
        // Owned victims only: the destructive work below runs on the captured set.
        // A newest successful login or a newer cycle publishing while this work runs
        // cannot be killed: newer calls were never in the victim set and the newer
        // timer (a newer cycle's ownership) is never cancelled afterwards.
        //
        // The v2Logger call is the existing production seam: a non-blocking log in
        // production and the deterministic test barrier between the owned commit and
        // the destructive work.
        v2Logger.d(message = "Reconnection timeout reached after ${RECONNECT_TIMEOUT}ms")
        victims.forEach { call ->
            call.setReconnectionTimeout()
        }
        victimReporters.filterNotNull().forEach { webRTCReporter ->
            webRTCReporter.stopStats()
        }
        // Global reset/error linearized with the ownership epoch under the same
        // monitor: both the ownership check and these (non-blocking) effects share
        // one critical section, so a newer epoch taking over is never reset and
        // never receives the stale error.
        // Note: we intentionally do NOT call peerConnection.disconnect()
        // because close()+dispose() on an active signaling thread causes
        // SIGSEGV in libjingle_peerconnection_so. The peer connections
        // will be garbage collected safely without explicit disposal.
        synchronized(this) {
            if (v2TimerEpoch == epoch) {
                callNotOngoing()
                emitError(
                    TelnyxErrorCode.RECONNECTION_EXHAUSTED.code,
                    "Reconnection timeout after ${RECONNECT_TIMEOUT / TIMEOUT_DIVISOR} seconds"
                ) // RECONNECTION_EXHAUSTED
            }
        }
    }

    /**
     * Cancels the reconnection timer if it's running.
     */
    private fun cancelReconnectionTimer() {
        synchronized(this) { cancelReconnectionTimerUnderGuard() }
    }

    /** Guard-local cancel: callers already holding the [TelnyxClient] monitor. */
    private fun cancelReconnectionTimerUnderGuard() {
        v2Logger.d(message = "Cancelling reconnection timer")
        reconnectTimeOutJob?.cancel()
        reconnectTimeOutJob = null
    }

    override fun onConnectionEstablished() {
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onConnectionEstablished",
                this@TelnyxClient.javaClass.simpleName
            )
        )
        emitSocketResponse(SocketResponse.established())
        
        // Emit initial connection metrics with CALCULATING state
        val initialMetrics = SocketConnectionMetrics(quality = SocketConnectionQuality.CALCULATING)
        _socketConnectionMetricsFlow.tryEmit(initialMetrics)
        currentSocketConnectionMetrics = initialMetrics

        emitConnectionStatus(ConnectionStatus.CONNECTED)
    }

    private fun bindV2ProviderIds(token: com.telnyx.webrtc.sdk.observability.V2CaptureToken, params: JsonObject) {
        fun id(name: String): String? = params.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        callReportV2?.providerIds(token, id("telnyx_leg_id"), id("telnyx_session_id"))
    }

    internal fun socketFailureWillRetry(): Boolean = !socket.isLoggedIn && !reconnecting &&
        !isDeclinePushConnection && (credentialSessionConfig != null || tokenSessionConfig != null)

    override fun onErrorReceived(jsonObject: JsonObject, errorCode: Int?) {
        @Suppress("SwallowedException")
        val id = try {
            jsonObject.get("id")?.asString
        } catch (e: Exception) {
            null
        }
        val isAttachCallError = errorCode == null && id != null && attachCallId == id
        if (isAttachCallError) {
            v2Logger.d(message = "Call Failed Error Received")
            val pendingCallId = claimPendingPushCall()
            reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED,
                pendingCallId, com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Reason.SERVER_ERROR)
            emitError(TelnyxErrorCode.SESSION_NOT_REATTACHED.code, "Call Failed") // SESSION_NOT_REATTACHED — attach-call failure means reattach failed
            pendingCallId?.let {
                emitPendingPushBye(it, "REMOTE_ERROR", null, null, null)
            }
            disconnectTransientDeclinePushConnection()
            return
        }
        // Login with attach_call=true can fail before an INVITE exists. This is
        // telemetry only; preserve the original authentication/retry behavior below.
        val failedPushLogin = synchronized(this) {
            pushTelemetryAttachAttempt?.takeIf { isCallPendingFromPush && it.second.toString() == id }?.first
        }
        reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED,
            failedPushLogin, com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Reason.SERVER_ERROR)
        @Suppress("SwallowedException")
        val errorMessage = try {
            jsonObject.get("error")?.asJsonObject?.get("message")?.asString
        } catch (e: Exception) {
            "Unknown error"
        } ?: "Unknown error"
        v2Logger.d(message = "onErrorReceived $errorMessage, code: $errorCode")
        // JS parity (VSDK-393): the gateway returns JSON-RPC -32000 both for
        // unauthenticated requests and for auth loss mid-session. JS splits by
        // phase: pre-login -> LOGIN_FAILED (_handleLoginError), in-session ->
        // AUTHENTICATION_REQUIRED (auto re-login when autoReconnect is on).
        val mappedCode = if (errorCode == JSON_RPC_AUTH_ERROR && socket.isLoggedIn) {
            TelnyxErrorCode.AUTHENTICATION_REQUIRED.code
        } else {
            mapServerErrorCode(errorCode)
        }
        emitError(mappedCode, errorMessage)
        disconnectTransientDeclinePushConnection()

        // If we're not logged in yet and not already reconnecting, schedule a single
        // retry with a jittered delay. This handles the case where a non-JSON-object
        // WebSocket payload (e.g. duplicate login response) caused a parse error
        // during the initial connect phase — instead of hanging until the 36s
        // socket timeout, the SDK reconnects automatically.
        val canRetryAfterParseError = socketFailureWillRetry()

        callReportV2?.rpcFailed(id, errorCode, errorMessage, canRetryAfterParseError)

        if (canRetryAfterParseError) {
            val jitteredDelay = RECONNECT_AFTER_PARSE_ERROR_MIN_MS +
                (Math.random() * RECONNECT_AFTER_PARSE_ERROR_JITTER_MS).toLong()
            v2Logger.d(message = "Scheduling reconnect after parse error in ${jitteredDelay}ms")
            reconnecting = true
            launchReconnectJob(jitteredDelay)
        }
    }

    override fun onByeReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.BYE.methodName)) {
            return
        }

        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onByeReceived JSON: %s",
                this.javaClass.simpleName,
                jsonObject.toString()
            )
        )

        try {
            val params = jsonObject.getAsJsonObject("params")
            val callIdString = params?.get("callID")?.asString

            if (callIdString == null) {
                v2Logger.e(message = "Received BYE without callID in params: $jsonObject")
                return
            }
            val callId = UUID.fromString(callIdString)

            val cause = params.get("cause")?.asString
            val causeCode = params.get("causeCode")?.asInt
            val sipCode = params.get("sipCode")?.asInt
            val sipReason = params.get("sipReason")?.asString

            val appFacingCallId = appCallId(callId)
            val byeCall = calls[callId]
            byeCall?.apply {
                callReportV2?.termination(v2CaptureToken, local = false)
                val terminationReason = CallTerminationReason(
                    cause = cause,
                    causeCode = causeCode,
                    sipCode = sipCode,
                    sipReason = sipReason
                )
                updateCallState(CallState.DONE(terminationReason))

                // Create the rich ByeResponse to be sent to the UI/ViewModel
                val byeResponseForUi = ByeResponse(
                    callId = appFacingCallId,
                    cause = cause,
                    causeCode = causeCode,
                    sipCode = sipCode,
                    sipReason = sipReason
                )

                client.emitSocketResponse(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.BYE.methodName,
                            byeResponseForUi
                        )
                    )
                )

                // Existing cleanup logic
                removeWebRTCReporter(callId)?.stopStats()

                // Log debug data for the ended call
                client.debugDataCollector.onCallEnded(callId, cause ?: "REMOTE_BYE")

                client.removeFromCalls(callId)
                client.callNotOngoing()
                resetCallOptions()
                client.stopMediaPlayer()
                peerConnection?.release()
                peerConnection = null
                answerResponse = null
                inviteResponse = null
                _transcript.clear()
                _transcriptUpdateFlow.tryEmit(emptyList())
            } ?: run {
                val pendingCallId = claimPendingPushCall()
                if (pendingCallId != null) {
                    v2Logger.d(message = "Received BYE for pending push call before INVITE: signaling=$callId push=$pendingCallId")
                    emitPendingPushBye(pendingCallId, cause, causeCode, sipCode, sipReason)
                } else {
                    v2Logger.w(message = "Received BYE for a callId not found in active calls: $callId")
                }
            }
        } catch (e: Exception) {
            v2Logger.e(message = "Error processing onByeReceived: ${e.message}")
        }
    }

    override fun onAnswerReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.ANSWER.methodName)) {
            return
        }

        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val callUuid = UUID.fromString(callId)
        
        // Mark call answered by remote milestone for outbound calls
        latencyTracker.markCallAnsweredByRemote(callUuid)
        
        val answeredCall = calls[callUuid]
        answeredCall?.apply {
            callReportV2?.timing(v2CaptureToken, "remote_answered_ms")
            bindV2ProviderIds(v2CaptureToken, params)
            val customHeaders =
                params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

            // Parse telnyx_call_control_id if present (for outbound flows: parked & bridged)
            val callControlId = params.get("telnyx_call_control_id")?.takeIf { !it.isJsonNull }?.asString ?: ""

            // Store the call control ID on the call object
            telnyxCallControlId = callControlId

            when {
                params.has("sdp") -> {
                    val stringSdp = params.get("sdp").asString

                    // Start benchmark for outbound call when answer SDP is received
                    CallTimingBenchmark.start(isOutbound = true)
                    CallTimingBenchmark.mark("answer_sdp_received")
                    // Track remote SDP received milestone
                    client.latencyTracker.markCallMilestone(UUID.fromString(callId), LatencyTracker.MILESTONE_REMOTE_SDP_RECEIVED)

                    // Check if remote party supports trickle ICE
                    val remoteSupportsTrickleIce = SdpUtils.hasTrickleIceCapability(stringSdp)
                    if (remoteSupportsTrickleIce) {
                        v2Logger.d(
                            tag = "TrickleICE",
                            message = "Remote party supports trickle ICE in ANSWER"
                        )
                    } else if (this.useTrickleIce) {
                        v2Logger.w(
                            tag = "TrickleICE",
                            message = "Local trickle ICE enabled but remote party does not support it"
                        )
                    }

                    // Extract ICE parameters from the answer SDP for candidate enhancement
                    if (this.useTrickleIce) {
                        remoteIceParameters = SdpUtils.extractIceParameters(stringSdp)
                        v2Logger.d(
                            tag = "TrickleICE",
                            message = "Extracted ICE parameters: $remoteIceParameters"
                        )
                    }

                    val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

                    // JS parity (VSDK-393): a second inbound ANSWER while the peer
                    // already has remote media negotiated -> DUPLICATE_INBOUND_ANSWER.
                    // Matches JS _registerInboundAnswerAttempt (BaseCall.ts): the
                    // answer() call is IGNORED on the duplicate path — applying a
                    // second ANSWER in stable state is rejected by libwebrtc and
                    // would surface SDP_SET_REMOTE_DESCRIPTION_FAILED (40004) with
                    // the call showing ACTIVE on broken media.
                    val duplicateAnswerGuard = peerConnection?.getRemoteDescription() != null
                    if (duplicateAnswerGuard) {
                        Logger.w(
                            message = "Duplicate inbound ANSWER for call $callId (remote description already set) — answer ignored"
                        )
                        emitWarning(TelnyxWarningCode.DUPLICATE_INBOUND_ANSWER.code, v2CaptureToken)
                    } else {
                        peerConnection?.onRemoteSessionReceived(sdp)
                        CallTimingBenchmark.mark("remote_description_set")
                        client.latencyTracker.markCallMilestone(UUID.fromString(callId), LatencyTracker.MILESTONE_REMOTE_SDP_SET)

                        // Process any queued ICE candidates after remote description is set
                        processQueuedIceCandidates(UUID.fromString(callId))

                        updateCallState(CallState.ACTIVE)

                        // Complete call latency tracking
                        client.debugDataCollector.addCallTimingsLogs(UUID.fromString(callId), client.latencyTracker.completeCallTracking(UUID.fromString(callId)))

                        val answerResponse = AnswerResponse(
                            appCallId(UUID.fromString(callId)),
                            stringSdp,
                            customHeaders?.toCustomHeaders() ?: arrayListOf(),
                            callControlId
                        )
                        this.answerResponse = answerResponse
                        client.emitSocketResponse(
                            SocketResponse.messageReceived(
                                ReceivedMessageBody(
                                    SocketMethod.ANSWER.methodName,
                                    answerResponse
                                )
                            )
                        )
                    }
                }

                earlySDP -> {
                    updateCallState(CallState.CONNECTING)
                    val stringSdp = peerConnection?.getLocalDescription()?.description
                    val answerResponse = AnswerResponse(
                        appCallId(UUID.fromString(callId)),
                        stringSdp!!,
                        customHeaders?.toCustomHeaders() ?: arrayListOf(),
                        callControlId
                    )
                    this.answerResponse = answerResponse
                    client.emitSocketResponse(
                        SocketResponse.messageReceived(
                            ReceivedMessageBody(
                                SocketMethod.ANSWER.methodName,
                                answerResponse
                            )
                        )
                    )
                    updateCallState(CallState.ACTIVE)
                    
                    // Complete call latency tracking
                    client.debugDataCollector.addCallTimingsLogs(UUID.fromString(callId), client.latencyTracker.completeCallTracking(UUID.fromString(callId)))
                }

                else -> {
                    // There was no SDP in the response, there was an error.
                    val reason = CallTerminationReason(
                        cause = "AnswerError",
                        sipReason = "No SDP in answer response"
                    )
                    updateCallState(CallState.DONE(reason))
                    client.removeFromCalls(UUID.fromString(callId))
                }
            }
            client.callOngoing()
            client.stopMediaPlayer()
        }
        answeredCall?.let {
            addToCalls(it)
        }
    }

    /**
     * Processes queued ICE candidates for a specific call after the remote description has been set.
     * This ensures that ICE candidates are added to the peer connection in the correct order.
     *
     * @param callId The UUID of the call to process queued candidates for
     */
    private fun processQueuedIceCandidates(callId: UUID) {
        synchronized(pendingIceCandidates) {
            val candidates = pendingIceCandidates.remove(callId)
            candidates?.let { queuedCandidates ->
                v2Logger.d(
                    tag = "processQueuedIceCandidates",
                    message = "Processing ${queuedCandidates.size} queued ICE candidates for call $callId"
                )

                val call = calls[callId]
                call?.let {
                    queuedCandidates.forEach { pendingCandidate ->
                        // Enhance candidate string with ICE parameters for consistency if trickle ICE is enabled
                        // Now we have access to remoteIceParameters since the remote description has been set
                        val finalCandidateString =
                            if (it.useTrickleIce && it.remoteIceParameters != null) {
                                SdpUtils.enhanceCandidateString(
                                    pendingCandidate.candidateString,
                                    it.remoteIceParameters!!
                                )
                            } else {
                                pendingCandidate.candidateString
                            }

                        val iceCandidate = IceCandidate(
                            pendingCandidate.sdpMid,
                            pendingCandidate.sdpMLineIndex,
                            finalCandidateString
                        )
                        it.peerConnection?.addIceCandidate(iceCandidate)
                        v2Logger.d(
                            tag = "processQueuedIceCandidates",
                            message = "Added queued ICE candidate for call $callId: ${pendingCandidate.candidateString}"
                        )
                    }
                    v2Logger.d(
                        tag = "processQueuedIceCandidates",
                        message = "Successfully processed all queued ICE candidates for call $callId"
                    )
                } ?: v2Logger.w(
                    tag = "processQueuedIceCandidates",
                    message = "No call found for ID: $callId while processing queued candidates"
                )
            } ?: v2Logger.d(
                tag = "processQueuedIceCandidates",
                message = "No queued ICE candidates to process for call $callId"
            )
        }
    }

    override fun onMediaReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.MEDIA.methodName)) {
            return
        }

        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val mediaCall = calls[UUID.fromString(callId)]
        mediaCall?.apply {
            callReportV2?.timing(v2CaptureToken, "early_media_ms")
            bindV2ProviderIds(v2CaptureToken, params)
            if (params.has("sdp")) {
                val stringSdp = params.get("sdp").asString
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, stringSdp)

                peerConnection?.onRemoteSessionReceived(sdp)
                // Set internal flag for early retrieval of SDP -
                // generally occurs when a ringback setting is applied in inbound call settings
                earlySDP = true

                val callerIDName =
                    if (params.has("caller_id_name")) params.get("caller_id_name").asString else ""
                val callerNumber =
                    if (params.has("caller_id_number")) params.get("caller_id_number").asString else ""

                val mediaResponse = MediaResponse(
                    appCallId(UUID.fromString(callId)),
                    callerIDName,
                    callerNumber,
                    sessionId,
                )
                client.emitSocketResponse(
                    SocketResponse.messageReceived(
                        ReceivedMessageBody(
                            SocketMethod.MEDIA.methodName,
                            mediaResponse
                        )
                    )
                )

            } else {
                // There was no SDP in the response, there was an error.
                val reason = CallTerminationReason(
                    cause = "MediaError",
                    sipReason = "No SDP in media response"
                )
                updateCallState(CallState.DONE(reason))
                client.removeFromCalls(UUID.fromString(callId))
            }

        }


        /*Stop local Media and play ringback from telnyx cloud*/
        stopMediaPlayer()
    }

    override fun onOfferReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.INVITE.methodName)) {
            return
        }

        if (jsonObject.has("params")) {
            v2Logger.d(
                message = v2Logger.formatMessage(
                    "[%s] :: onOfferReceived [%s]",
                    this@TelnyxClient.javaClass.simpleName,
                    jsonObject
                )
            )
            val offerCall = Call(
                context = context,
                client = this,
                socket = socket,
                sessionId = sessid,
                audioManager = audioManager!!,
                providedTurn = providedTurn!!,
                providedStun = providedStun!!
            ).apply {
                val params = jsonObject.getAsJsonObject("params")
                val offerCallId = UUID.fromString(params.get("callID").asString)
                val variables = inviteVariables(params)
                val appCallId = inviteAppCallId(offerCallId) ?: offerCallId
                // IMPORTANT: inviteAppCallId() MUST be evaluated before claimPendingPushCall().
                // inviteAppCallId() reads pendingPushAppCallId under synchronized(this); claimPendingPushCall()
                // then clears it under the same lock. The order matters because the claim nulls the field
                // the alias read depends on. If the read is moved after the claim, the push app call ID is
                // lost and the alias mapping (registerCallIdAlias) would use the socket ID as the app-facing ID.
                // INVITE atomically wins against BYE/timeout before the call is exposed.
                val attachedPushCall = claimPendingPushCall()
                reportPushMilestone(com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_ATTACH_SUCCEEDED,
                    attachedPushCall)
                registerCallIdAlias(appCallId, offerCallId)
                val remoteSdp = params.get("sdp").asString

                // Check if remote party supports trickle ICE
                val remoteSupportsTrickleIce = SdpUtils.hasTrickleIceCapability(remoteSdp)
                if (remoteSupportsTrickleIce) {
                    v2Logger.d(
                        tag = "TrickleICE",
                        message = "Remote party supports trickle ICE in OFFER"
                    )
                    // Only enable trickle ICE if both parties support it
                    // The Call captured the client default when this OFFER arrived;
                    // later default changes apply only to future calls.
                }

                // Extract ICE parameters from the offer SDP for candidate enhancement
                // This will be used later when receiving ICE candidates for this call
                remoteIceParameters = SdpUtils.extractIceParameters(remoteSdp)
                v2Logger.d(
                    tag = "TrickleICE",
                    message = "Extracted ICE parameters from OFFER: $remoteIceParameters"
                )

                val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
                if (voiceSdkID != null) {
                    v2Logger.d(message = "Voice SDK ID _ $voiceSdkID")
                    this@TelnyxClient.voiceSDKID = voiceSdkID
                    callReportV2?.signaling(connectionGeneration.get().toLong(), voiceSDKID, callReportV2ServerSessionId)
                    updateDebugDataCollectorUploadConfig()
                } else {
                    v2Logger.e(message = "No Voice SDK ID")
                }

                val callerName = params.get("caller_id_name").asString
                val callerNumber = params.get("caller_id_number").asString
                telnyxSessionId = UUID.fromString(params.get("telnyx_session_id").asString)
                telnyxLegId = UUID.fromString(params.get("telnyx_leg_id").asString)

                // Expose the app-facing ID while keeping the signaling ID for socket operations.
                callId = appCallId
                signalingCallId = offerCallId
                //retrieve custom headers (typed options carry signaling header NAMES only;
                // header values are never reported)
                val customHeaders =
                    params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray
                // t027: caller metadata comes from the INVITE signaling params (server-provided);
                // the destination is this client's logged-in SIP identity when known.
                // raw_call_options stays {} because the app has supplied no options yet.
                beginV2Capture(inbound = true, callerName = callerName, callerNumber = callerNumber,
                    destinationNumber = v2LocalSipIdentity(),
                    customHeaders = customHeaders?.toCustomHeaders()?.associate { it.name to it.value })
                bindV2ProviderIds(v2CaptureToken, params)
                
                // Start latency tracking when invite is received (for answer delay calculation)
                client.latencyTracker.startCallTracking(
                    offerCallId,
                    isOutbound = false,
                    useTrickleIce = this.useTrickleIce,
                )
                client.latencyTracker.markInviteReceived(offerCallId)

                // Notify debug data collector that a new call has started
                client.debugDataCollector.onCallStarted(offerCallId, telnyxSessionId, telnyxLegId)
                client.debugDataCollector.updateCallMetadata(
                    callId = offerCallId,
                    callerNumber = callerNumber,
                    destinationNumber = "",
                    direction = "inbound"
                )

                peerConnection = Peer(
                    context,
                    client,
                    providedTurn,
                    providedStun,
                    offerCallId,
                    prefetchIceCandidates,
                    getForceRelayCandidate(),
                    isAnswering = true,
                    onIceCandidateAdd = { candidate ->
                        addIceCandidateInternal(candidate)
                    },
                    audioConstraints = null, // Use defaults for incoming calls
                    initialV2CaptureToken = v2CaptureToken
                ).also {
                    it.v2CaptureToken = v2CaptureToken
                    // Create reporter — always start so quality metrics are available
                    // (independent of debug flag since VSDK-608)
                    if (enableCallQualityReports || isSocketDebug || callReportV2 != null) {
                        val webRTCReporter = WebRTCReporter(
                            socket,
                            callId,
                            telnyxLegId?.toString(),
                            it,
                            false,
                            isSocketDebug,
                            enableCallQualityReports,
                            callQualityReportInterval,
                            sendWebRTCStatsViaSocket,
                            debugDataCollector,
                            v2CaptureToken
                        )
                        webRTCReporter.onCallQualityChange = { metrics ->
                            onCallQualityChange?.invoke(metrics)
                        }
                        webRTCReporter.startStats()
                        addWebRTCReporter(callId, webRTCReporter)
                    }
                }

                peerConnection?.onRemoteSessionReceived(
                    SessionDescription(
                        SessionDescription.Type.OFFER,
                        remoteSdp
                    )
                )

                val inviteResponse = InviteResponse(
                    appCallId,
                    remoteSdp,
                    callerName,
                    callerNumber,
                    sessionId,
                    customHeaders = customHeaders?.toCustomHeaders() ?: arrayListOf(),
                    variables = variables
                )
                this.inviteResponse = inviteResponse

            }
            offerCall.client.playRingtone()
            addToCalls(offerCall)
            offerCall.client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.INVITE.methodName,
                        offerCall.inviteResponse
                    )
                )
            )
        } else {
            v2Logger.d(
                message = v2Logger.formatMessage(
                    "[%s] :: Invalid offer received, missing required parameters [%s]",
                    this.javaClass.simpleName, jsonObject
                )
            )
        }

    }

    fun isSocketConnected(): Boolean {
        return socket.isConnected
    }

    /**
     * Uses the existing socket reconnect/login/attach flow as recovery fallback.
     *
     * @param callId the recovering call; a relay-only override is recorded for
     * this call only and consumed by its own attach.
     */
    internal fun requestCallRecoveryReattach(
        callId: UUID,
        forceRelayCandidateForRecovery: Boolean = false
    ): Boolean =
        synchronized(this) {
            val recoveringCallId = signalingCallId(callId)
            when {
                isDeclinePushConnection -> {
                    v2Logger.w(message = "Ignoring call recovery reattach in decline-push mode")
                    false
                }
                credentialSessionConfig == null && tokenSessionConfig == null -> {
                    v2Logger.e(message = "Cannot reattach call without a saved session configuration")
                    false
                }
                reconnecting -> {
                    if (forceRelayCandidateForRecovery) {
                        forceRelayRecoveredCallIds.add(recoveringCallId)
                    }
                    v2Logger.d(message = "Call recovery reattach is already in progress")
                    true
                }
                else -> {
                    if (forceRelayCandidateForRecovery) {
                        forceRelayRecoveredCallIds.add(recoveringCallId)
                    } else {
                        forceRelayRecoveredCallIds.remove(recoveringCallId)
                    }
                    reconnecting = true
                    v2Logger.w(message = "Starting reconnect/reattach for call recovery")
                    launchReconnectJob()
                    true
                }
            }
        }

    /**
     * Consume the relay-only override recorded for this call's recovery, if
     * any. Only the attach for the requesting call receives it.
     */
    internal fun consumeRecoveryRelayOverride(callId: UUID): Boolean =
        forceRelayRecoveredCallIds.remove(callId)

    override fun onRingingReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.RINGING.methodName)) {
            return
        }

        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onRingingReceived [%s]",
                this@TelnyxClient.javaClass.simpleName,
                jsonObject
            )
        )
        val params = jsonObject.getAsJsonObject("params")
        val callId = params.get("callID").asString
        val callUuid = UUID.fromString(callId)
        
        // Mark remote ringing milestone for outbound calls
        latencyTracker.markRemoteRinging(callUuid)
        
        val ringingCall = calls[callUuid]

        ringingCall?.apply {
            callReportV2?.timing(v2CaptureToken, "remote_ringing_ms")
            bindV2ProviderIds(v2CaptureToken, params)
            telnyxSessionId = if (params.has("telnyx_session_id")) {
                UUID.fromString(params.get("telnyx_session_id").asString)
            } else {
                UUID.randomUUID()
            }
            telnyxLegId = if (params.has("telnyx_leg_id")) {
                UUID.fromString(params.get("telnyx_leg_id").asString)
            } else {
                UUID.randomUUID()
            }

            // Update debug data collector with Telnyx identifiers
            client.debugDataCollector.updateTelnyxIdentifiers(
                UUID.fromString(callId),
                telnyxSessionId,
                telnyxLegId
            )

            val customHeaders =
                params.get("dialogParams")?.asJsonObject?.get("custom_headers")?.asJsonArray

            val ringingResponse = RingingResponse(
                appCallId(callUuid),
                params.get("caller_id_name").asString,
                params.get("caller_id_number").asString,
                sessionId,
                customHeaders?.toCustomHeaders() ?: arrayListOf()
            )
            client.emitSocketResponse(
                SocketResponse.messageReceived(
                    ReceivedMessageBody(
                        SocketMethod.RINGING.methodName,
                        ringingResponse
                    )
                )
            )
        }
    }

    override fun onIceCandidateReceived(iceCandidate: IceCandidate) {
        if (shouldIgnoreDeclinePushCallEvent("ICE_CANDIDATE")) {
            return
        }

        call?.apply {
            updateCallState(CallState.CONNECTING)
        }
    }

    override fun onDisablePushReceived(jsonObject: JsonObject) {
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: onDisablePushReceived [%s]",
                this@TelnyxClient.javaClass.simpleName,
                jsonObject
            )
        )
        val errorMessage = jsonObject.get("result").asJsonObject.get("message").asString
        val disablePushResponse = DisablePushResponse(
            errorMessage.contains(DisablePushResponse.SUCCESS_KEY),
            errorMessage
        )
        emitSocketResponse(
            SocketResponse.messageReceived(
                ReceivedMessageBody(
                    SocketMethod.RINGING.methodName,
                    disablePushResponse
                )
            )
        )
    }

    override fun onAttachReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.ATTACH.methodName)) {
            return
        }

        // reset reconnecting state
        reconnecting = false
        reconnectionRetryCounter.set(0)
        val params = jsonObject.getAsJsonObject("params")
        val offerCallId = UUID.fromString(params.get("callID").asString)

        calls[offerCallId]?.copy(
            context = context,
            client = this,
            socket = socket,
            sessionId = sessid,
            audioManager = audioManager!!,
            providedTurn = providedTurn!!,
            providedStun = providedStun!!,
            mutableCallStateFlow = calls[offerCallId]!!.mutableCallStateFlow,
        )?.apply {

            val remoteSdp = params.get("sdp").asString
            val voiceSdkID = jsonObject.getAsJsonPrimitive("voice_sdk_id")?.asString
            if (voiceSdkID != null) {
                v2Logger.d(message = "Voice SDK ID _ $voiceSdkID")
                this@TelnyxClient.voiceSDKID = voiceSdkID
            callReportV2?.signaling(connectionGeneration.get().toLong(), voiceSDKID, callReportV2ServerSessionId)
                updateDebugDataCollectorUploadConfig()
            } else {
                v2Logger.e(message = "No Voice SDK ID")
            }

            // val callerName = params.get("caller_id_name").asString
            val callerNumber = params.get("caller_id_number").asString
            val (reattachedSessionId, reattachedLegId) = reattachIdentifiers(
                params,
                telnyxSessionId,
                telnyxLegId
            )
            telnyxSessionId = reattachedSessionId
            telnyxLegId = reattachedLegId

            // Preserve the app-facing ID across reattach while continuing to signal with callID.
            callId = appCallId(offerCallId)
            signalingCallId = offerCallId
            // t027: reattach carries signaling caller metadata; the app's answer-time
            // options are never back-filled into the earlier call_started record.
            beginV2Capture(inbound = true, reattach = true,
                callerNumber = callerNumber,
                destinationNumber = v2LocalSipIdentity(),
                customHeaders = params.get("dialogParams")?.asJsonObject
                    ?.get("custom_headers")?.asJsonArray?.toCustomHeaders()?.associate { it.name to it.value })
            bindV2ProviderIds(v2CaptureToken, params)

            // A recovery reattach is still the same call, so retain all previously collected
            // report data. A cold-start push attach initializes a report here instead.
            val resumedExistingReport = client.debugDataCollector.onCallReattached(
                offerCallId,
                telnyxSessionId,
                telnyxLegId
            )
            if (!resumedExistingReport) {
                client.debugDataCollector.updateCallMetadata(
                    callId = offerCallId,
                    callerNumber = callerNumber,
                    destinationNumber = "",
                    direction = "inbound"
                )
            }

            val forceRelayForRecoveredCall = consumeRecoveryRelayOverride(offerCallId)
            peerConnection = Peer(
                context,
                client,
                providedTurn,
                providedStun,
                offerCallId,
                prefetchIceCandidates,
                getForceRelayCandidate() || forceRelayForRecoveredCall,
                isAnswering = true,
                onIceCandidateAdd = null,
                audioConstraints = null, // Use defaults for reattached calls
                initialV2CaptureToken = v2CaptureToken
            ).also {
                it.v2CaptureToken = v2CaptureToken
                // Create reporter — always start so quality metrics are available
                // (independent of debug flag since VSDK-608)
                if (enableCallQualityReports || isSocketDebug || callReportV2 != null) {
                    val webRTCReporter = WebRTCReporter(
                        socket,
                        callId,
                        telnyxLegId?.toString(),
                        it,
                        false,
                        isSocketDebug,
                        enableCallQualityReports,
                        callQualityReportInterval,
                            sendWebRTCStatsViaSocket,
                        debugDataCollector,
                        v2CaptureToken
                    )
                    webRTCReporter.onCallQualityChange = { metrics ->
                        onCallQualityChange?.invoke(metrics)
                    }
                    webRTCReporter.startStats()
                    addWebRTCReporter(callId, webRTCReporter)
                }
            }

            peerConnection?.startLocalAudioCapture()

            peerConnection?.onRemoteSessionReceived(
                SessionDescription(
                    SessionDescription.Type.OFFER,
                    remoteSdp
                )
            )

            // Note: Codec preferences not applied during reconnection
            // Using default codec order during call recovery
            peerConnection?.answer(AppSdpObserver())

            val iceCandidateTimer = Timer()
            iceCandidateTimer.schedule(
                timerTask {
                    acceptReattachCall(callId, callerNumber)
                },
                Call.ICE_CANDIDATE_DELAY
            )
            calls[this.callId]?.updateCallState(CallState.ACTIVE)
            this.updateCallState(calls[this.callId]?.callStateFlow?.value ?: CallState.ACTIVE)
            calls[this.callId] = this.apply {
                updateCallState(CallState.ACTIVE)
            }
        } ?: run {
            v2Logger.e(message = "Call not found for Attach")
            // JS parity (VSDK-393): attach response for an unknown callID during
            // reattach recovery -> UNKNOWN_REATTACHED_SESSION warning.
            emitWarning(TelnyxWarningCode.UNKNOWN_REATTACHED_SESSION.code)
        }
    }

    override fun setCallRecovering() {
        call?.setCallRecovering()
    }

    override fun pingPong(socketConnectionMetrics: SocketConnectionMetrics?) {
        v2Logger.d(
            message = v2Logger.formatMessage(
                "[%s] :: pingPong - Quality: %s, Interval: %sms, Jitter: %sms",
                this@TelnyxClient.javaClass.simpleName,
                socketConnectionMetrics?.quality,
                socketConnectionMetrics?.averageIntervalMs,
                socketConnectionMetrics?.jitterMs
            )
        )
        
        // Store and emit the connection metrics
        socketConnectionMetrics?.let { metrics ->
            currentSocketConnectionMetrics = metrics
            _socketConnectionMetricsFlow.tryEmit(metrics)
        }
    }

    internal fun onRemoteSessionErrorReceived(errorMessage: String?, token: com.telnyx.webrtc.sdk.observability.V2CaptureToken? = null) {
        stopMediaPlayer()
        errorMessage?.let { emitError(TelnyxErrorCode.SDP_SET_REMOTE_DESCRIPTION_FAILED.code, it, token) } // SDP_SET_REMOTE_DESCRIPTION_FAILED
    }

    /**
     * Disconnect from the TxSocket and unregister the provided network callback
     *
     * If we're in the middle of a reconnection attempt with active calls and the server
     * closes the connection (e.g., session expired), we should retry with exponential
     * backoff instead of immediately giving up.
     *
     * @see [ConnectivityHelper]
     * @see [TxSocket]
     */
    override fun onDisconnect() {
        disconnectInternal(allowReconnectRetry = true)
    }

    private fun disconnectInternal(allowReconnectRetry: Boolean) {
        // Check if we should retry reconnection instead of giving up
        if (allowReconnectRetry && reconnecting && getActiveCalls().isNotEmpty()) {
            if (reconnectionRetryCounter.get() < MAX_RECONNECTION_RETRIES) {
                val retryCount = reconnectionRetryCounter.incrementAndGet()
                val backoffDelay = RECONNECTION_RETRY_BASE_DELAY * (1L shl (retryCount - 1))
                
                v2Logger.d(
                    message = v2Logger.formatMessage(
                        "[%s] :: Server closed connection during reconnection. Retrying (%d/%d) in %dms",
                        this@TelnyxClient.javaClass.simpleName,
                        retryCount,
                        MAX_RECONNECTION_RETRIES,
                        backoffDelay
                    )
                )
                
                // Destroy current socket before retry
                socket.destroy()
                
                // Schedule retry with exponential backoff
                launchReconnectJob(backoffDelay)
                return
            } else {
                v2Logger.d(
                    message = v2Logger.formatMessage(
                        "[%s] :: Max reconnection retries (%d) reached. Giving up.",
                        this@TelnyxClient.javaClass.simpleName,
                        MAX_RECONNECTION_RETRIES
                    )
                )
                // Reset retry counter and fall through to normal disconnect handling
                reconnectionRetryCounter.set(0)
            }
        }
        
        // Normal disconnect handling
        emitSocketResponse(SocketResponse.disconnect())
        emitConnectionStatus(ConnectionStatus.DISCONNECTED)
        invalidateGatewayResponseTimer()
        resetGatewayCounters()
        unregisterNetworkCallback()
        
        // Reset reconnection state
        reconnecting = false
        forceRelayRecoveredCallIds.clear()
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectionRetryCounter.set(0)
        nextConnectionGeneration()
        socketReconnection?.destroy()
        socketReconnection = null
        
        // Clear connection metrics on disconnect
        currentSocketConnectionMetrics = null
        
        cancelSocketConnectJob()
        cancelAcceptCallJobs()
        socket.destroy()
        clearPendingPushCall()
        clearTransientDeclinePushMode()
    }

    private fun clearPendingPushCall() {
        synchronized(this) {
            pushInviteTimeoutJob?.cancel()
            pushInviteTimeoutJob = null
            isCallPendingFromPush = false
            pendingPushAppCallId = null
            pushMetaData = null
        }
    }

    /**
     * Handles AI conversation messages received from the socket
     * Processes transcript updates and widget settings
     *
     * @param jsonObject the socket response containing AI conversation data
     */
    override fun onAiConversationReceived(jsonObject: JsonObject) {
        v2Logger.i(message = "AI CONVERSATION RECEIVED :: $jsonObject")

        try {
            val aiConversationResponse =
                Gson().fromJson(jsonObject, AiConversationResponse::class.java)
            val params = aiConversationResponse.aiConversationParams

            // Store widget settings if available
            params?.widgetSettings?.let { settings ->
                _currentWidgetSettings = settings
                v2Logger.i(message = "Widget settings updated :: $_currentWidgetSettings")
            }

            // Process message for transcript extraction
            processAiConversationForTranscript(params)

            // Emit socket response
            val receivedMessageBody = ReceivedMessageBody(
                method = SocketMethod.AI_CONVERSATION.methodName,
                result = aiConversationResponse
            )
            emitSocketResponse(SocketResponse.aiConversation(receivedMessageBody))

        } catch (e: Exception) {
            v2Logger.e(message = "Error processing AI conversation message: ${e.message}")
        }
    }

    /**
     * Handles modify messages received from the socket (e.g., updateMedia responses)
     * Processes ICE renegotiation responses and forwards them to the appropriate peer
     *
     * @param jsonObject the socket response containing modify data
     */
    override fun onModifyReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.MODIFY.methodName)) {
            return
        }

        v2Logger.i(message = "MODIFY RECEIVED :: $jsonObject")

        try {
            // The updateMedia response is nested inside the "result" object
            val resultObject = jsonObject.getAsJsonObject("result")
            val updateMediaResponse = Gson().fromJson(resultObject, UpdateMediaResponse::class.java)

            // Find the peer with the matching call ID and handle the response
            updateMediaResponse.callId.let { callId ->
                // find the call with this call ID, and take the peer from it
                val call = calls[callId]
                val peer = call?.peerConnection
                if (peer != null) {
                    val responseId = jsonObject.get("id")
                        ?.takeUnless { it.isJsonNull }
                        ?.asString
                    if (responseId == null) {
                        // Preserve compatibility with legacy responses that
                        // omitted the JSON-RPC id.
                        peer.handleUpdateMediaResponse(updateMediaResponse.sdp)
                    } else {
                        peer.handleUpdateMediaResponse(updateMediaResponse.sdp, responseId)
                    }
                } else {
                    v2Logger.w(message = "No peer found for call ID: $callId in updateMedia response")
                }
            }

        } catch (e: Exception) {
            v2Logger.e(message = "Error processing modify message: ${e.message}")
            v2Logger.e(message = "JSON structure: ${jsonObject.toString()}")
        }
    }

    /**
     * Process AI conversation messages for transcript extraction
     */
    private fun processAiConversationForTranscript(params: AiConversationParams?) {
        if (params?.type == null) return

        when (params.type) {
            "conversation.item.created" -> handleConversationItemCreated(params)
            "response.text.delta" -> handleResponseTextDelta(params)
            // Other AI conversation message types are ignored for transcript
        }
    }

    /**
     * Handle user speech transcript from conversation.item.created messages
     */
    private fun handleConversationItemCreated(params: AiConversationParams) {
        val item = params.item
        if (item?.role != TranscriptItem.ROLE_USER || item.status != "completed") {
            return // Only handle completed user messages
        }

        // Extract text content from all content items with text or transcript
        val content = item.content
            ?.mapNotNull { it.transcript ?: it.text }
            ?.joinToString(" ") ?: ""

        // Extract image URLs from all content items with imageUrl
        val images = item.content
            ?.mapNotNull { it.imageUrl?.url }
            ?.takeIf { it.isNotEmpty() }

        if ((content.isNotEmpty() || images?.isNotEmpty() == true) && item.id != null) {
            val transcriptItem = TranscriptItem(
                id = item.id,
                role = TranscriptItem.ROLE_USER,
                content = content,
                images = images,
                timestamp = Date()
            )

            _transcript.add(transcriptItem)
            _transcriptUpdateFlow.tryEmit(_transcript.toList())
        }
    }

    /**
     * Handle AI response text deltas from response.text.delta messages
     */
    private fun handleResponseTextDelta(params: AiConversationParams) {
        val delta = params.delta ?: return
        val itemId = params.itemId ?: return

        // Initialize buffer for this response if not exists
        if (!assistantResponseBuffers.containsKey(itemId)) {
            assistantResponseBuffers[itemId] = StringBuilder()
        }
        assistantResponseBuffers[itemId]?.append(delta)

        // Create or update transcript item for this response
        val existingIndex = _transcript.indexOfFirst { it.id == itemId }
        val currentContent = assistantResponseBuffers[itemId]?.toString() ?: ""

        if (existingIndex >= 0) {
            // Update existing transcript item with accumulated content
            _transcript[existingIndex] = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = _transcript[existingIndex].timestamp,
                isPartial = true
            )
        } else {
            // Create new transcript item
            val transcriptItem = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = Date(),
                isPartial = true
            )
            _transcript.add(transcriptItem)
        }

        _transcriptUpdateFlow.tryEmit(_transcript.toList())
    }

    /**
     * Gets the forceRelayCandidate setting from the current session config
     */
    internal fun getForceRelayCandidate(): Boolean {
        return credentialSessionConfig?.forceRelayCandidate
            ?: tokenSessionConfig?.forceRelayCandidate
            ?: false
    }

    /**
     * Returns a list of audio codecs supported by WebRTC for this device.
     *
     * This method creates a temporary WebRTC peer connection to query the
     * actual audio codecs supported by the WebRTC library. The temporary peer connection is
     * properly disposed of after querying. This ensures the returned codec list matches exactly
     * what WebRTC will use during actual calls.
     *
     * **Common codecs** returned include: Opus, PCMU, PCMA, G722, RED, CN, and telephone-event.
     *
     * **Usage**:
     * - Call this method **before** initiating a call to get the list of supported WebRTC codecs
     * - Use the returned list to construct your preferred codec order
     * - Pass your preferences to [newInvite] or [acceptCall] via the `preferredCodecs` parameter
     *
     * **For runtime codec queries**: If you need to query the exact codecs negotiated during an
     * active call, use [Call.getAvailableAudioCodecs] instead, which queries the active peer
     * connection without creating a temporary one.
     *
     * @return List of [AudioCodec] objects representing WebRTC-supported audio codecs
     *
     * @see Call.getAvailableAudioCodecs for runtime codec queries during active calls
     * @see newInvite
     * @see acceptCall
     *
     * @sample
     * ```kotlin
     * // Query supported codecs before making a call
     * val supportedCodecs = telnyxClient.getSupportedAudioCodecs()
     * println("WebRTC supports: ${supportedCodecs.map { it.mimeType }}")
     *
     * // Prefer Opus, then PCMU as fallback
     * val preferredCodecs = supportedCodecs.filter {
     *     it.mimeType == "audio/opus" || it.mimeType == "audio/PCMU"
     * }
     *
     * // Use in call
     * telnyxClient.newInvite(
     *     callerName = "John",
     *     callerNumber = "+1234567890",
     *     destinationNumber = "sip:destination",
     *     clientState = "state",
     *     preferredCodecs = preferredCodecs
     * )
     * ```
     */
    fun getSupportedAudioCodecs(): List<AudioCodec> {
        return try {
            v2Logger.d(message = "Querying WebRTC audio codecs via Peer companion object")

            // Use the efficient companion object method - no temporary Peer needed!
            val codecs = Peer.getSupportedAudioCodecs(context)

            v2Logger.d(message = "Retrieved ${codecs.size} audio codecs: ${codecs.map { it.mimeType }}")
            codecs
        } catch (e: Exception) {
            v2Logger.e(message = "Error retrieving supported audio codecs: ${e.message}")
            emptyList()
        }
    }

    override fun onCandidateReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.CANDIDATE.methodName)) {
            return
        }

        v2Logger.d(tag = "onCandidateReceived", message = "ICE CANDIDATE RECEIVED :: $jsonObject")

        if (jsonObject.has("params")) {
            val params = jsonObject.get("params").asJsonObject

            if (CandidateUtils.hasRequiredCandidateFields(params)) {
                val candidateString =
                    CandidateUtils.normalizeCandidateString(params.get("candidate").asString)
                val sdpMid = params.get("sdpMid").asString
                val sdpMLineIndex = params.get("sdpMLineIndex").asInt

                val callId = CandidateUtils.extractCallIdFromCandidate(params)

                // Process the candidate if we found a call ID
                callId?.let { id: UUID ->
                    processAndQueueCandidate(id, sdpMid, sdpMLineIndex, candidateString)
                } ?: v2Logger.w(
                    tag = "onCandidateReceived",
                    message = "Could not extract call ID from candidate message"
                )
            } else {
                v2Logger.w(
                    tag = "onCandidateReceived",
                    message = "Candidate message missing required fields (candidate, sdpMid, or sdpMLineIndex)"
                )
            }
        }
    }

    /**
     * Processes and queues the ICE candidate for the specified call.
     */
    private fun processAndQueueCandidate(
        callId: UUID,
        sdpMid: String,
        sdpMLineIndex: Int,
        candidateString: String
    ) {
        val call = calls[callId]
        call?.let {
            // Create pending ICE candidate and queue it instead of immediately adding
            // Note: We don't enhance the candidate string here because remoteIceParameters 
            // won't be available until after the remote description is set in onAnswerReceived
            val pendingCandidate = PendingIceCandidate(
                callId = callId,
                sdpMid = sdpMid,
                sdpMLineIndex = sdpMLineIndex,
                candidateString = candidateString,
                enhancedCandidateString = candidateString // Store original for now, will enhance later
            )

            // Add to pending candidates map
            synchronized(pendingIceCandidates) {
                val candidates = pendingIceCandidates.getOrPut(callId) { mutableListOf() }
                candidates.add(pendingCandidate)
                v2Logger.d(
                    tag = "onCandidateReceived",
                    message = "Queued ICE candidate for call $callId. Total queued: ${candidates.size}"
                )
            }
        } ?: v2Logger.w(tag = "onCandidateReceived", message = "No call found for ID: $callId")
    }

    override fun onEndOfCandidatesReceived(jsonObject: JsonObject) {
        if (shouldIgnoreDeclinePushCallEvent(SocketMethod.END_OF_CANDIDATES.methodName)) {
            return
        }

        v2Logger.d(message = "END OF CANDIDATES RECEIVED :: $jsonObject")
    }

    override fun onCallReportIdReceived(callReportId: String) {
        this.callReportId = callReportId
        v2Logger.d(message = "Received call_report_id: $callReportId")
        updateDebugDataCollectorUploadConfig()
    }

    /**
     * Updates the debug data collector with the upload configuration.
     * Upload will occur when callReportId, voiceSDKID, and hostAddress are all set.
     */
    private fun updateDebugDataCollectorUploadConfig() {
        debugDataCollector.setUploadConfig(
            callReportId = callReportId,
            voiceSDKID = voiceSDKID,
            hostAddress = providedHostAddress
        )
    }

    /**
     * Disconnects the TelnyxClient, resets all internal states, and stops any ongoing audio playback.
     * This method should be called when the client is no longer needed or when the user logs out.
     */
    fun disconnect() {
        callReportV2?.suspendTelemetry()
        v2Logger.d(message = "Disconnecting TelnyxClient and clearing states")
        cancelReconnectionTimer()
        // JS parity (VSDK-393): clear any scheduled token expiry warning.
        cancelTokenExpiryTimeout()
        disconnectInternal(allowReconnectRetry = false)
        // Keep clientScope reusable for later reconnects; socket.destroy() cancels socket-owned work.
        clientScope.coroutineContext.cancelChildren()
    }

    private fun addWebRTCReporter(callId: UUID, webRTCReporter: WebRTCReporter) {
        webRTCReportersMap[callId] = webRTCReporter
    }

    private fun removeWebRTCReporter(callId: UUID): WebRTCReporter? {
        return webRTCReportersMap.remove(callId)
    }

    private fun getWebRTCReporter(callId: UUID): WebRTCReporter? {
        return webRTCReportersMap[callId]
    }
}
