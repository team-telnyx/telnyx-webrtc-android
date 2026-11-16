package com.telnyx.webrtc.sdk.observability

import android.os.SystemClock
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.TelnyxErrorCode
import com.telnyx.webrtc.sdk.model.CallState as NativeCallState
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.*
import com.telnyx.webrtc.mobile.shared.observability.core.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/** Thin native integration. Shared runtime owns schemas, sequencing, metrics and independent transport. */
class AndroidV2Capture(private val client: TelnyxClient,
    private val config: CallReportV2Config = CallReportV2Config(captureOnly = true, mirrorConsole = false),
    private val constructing: Boolean = false,
    clock: V2Clock = systemClock(),
    private val suppressCreationEvents: Boolean = false) {
    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        fun systemClock(): V2Clock = object : V2Clock {
            override fun monotonicMs() = System.nanoTime() / NANOS_PER_MS
            override fun wallTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())
        }
        const val INGRESS_CAPACITY = 256
        const val MAX_ACTIVE_CALLS = 128
        const val SAMPLE_INTERVAL_MS = 1000L
        const val MAX_SIGNALING_BYTES = 64 * 1024
        const val MAX_DEVICE_COUNT = 128
        const val MAX_GATEWAY_CHECKS = 16
        const val GATEWAY_CHECK_TIMEOUT_MS = 3000L
        const val MAX_DETAIL_LENGTH = 256
        const val MAX_PROVIDER_ID_LENGTH = 128
        const val MAX_CANDIDATE_LENGTH = 4096
        const val MAX_CANDIDATES_PER_DESCRIPTION = 256
        const val MAX_CANDIDATES_PER_CALL = 512
        val BUSY_SIP_CODES = setOf(486, 600)
        val NO_ANSWER_SIP_CODES = setOf(480, 408)
        // t029: hard cap on playout observations per call (lifetime, retained across peer
        // replacement) so alternating observations can never emit every tick indefinitely.
        const val PLAYOUT_OBSERVATION_CAP = 6
    }
    // t029: test-injectable startup facts. Public ABI is unchanged — this is an internal
    // member; when unset, facts are read live from the host environment at emission time.
    @set:JvmSynthetic
    internal var startupFactsOverride: V2StartupFacts? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = clock
    @Volatile private var effectiveConfig: CallReportV2Config = config
    @Volatile private var anonymousUpload = false
    private val runtime = createNativeV2Runtime(scope, client, config, clock) {
        client.callReportV2DisabledByServer.set(true)
        detachLifecycle()
    }
    private val ingress = Channel<suspend () -> Unit>(INGRESS_CAPACITY)
    private val dropped = AtomicLong()
    private val ingressStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Capture(val lease: V2CallLease, val began: Long, var state: CallState = CallState.NEW,
        var answered: Boolean = false, var samples: Int = 0, var lastSample: Long? = null,
        var terminal: NativeCallState? = null, var answeredAt: Long? = null,
        val timings: MutableMap<String, Double> = mutableMapOf(), val inbound: Boolean,
        var legId: String? = null, var providerSessionId: String? = null,
        var localEnd: Boolean? = null, var timingsSent: Boolean = false,
        var peerMedia: V2PeerMedia? = null, var media: CallMediaChangedPayload? = null, var mediaPairId: String? = null,
        var iceGeneration: Int = 1, var remoteIceGeneration: Int = 1, var localUfrag: String? = null, var remoteUfrag: String? = null,
        var gatheringStartedAt: Long? = null, var remoteDescriptionAt: Long? = null,
        val candidates: MutableSet<String> = mutableSetOf(),
        // t027: latest measured device counts (null until the observer first delivers an
        // inventory; 0 is a measured known-empty). Retained so every subsequent full media
        // snapshot keeps the counts instead of only the transient device-change event.
        var measuredInputDeviceCount: Double? = null, var measuredOutputDeviceCount: Double? = null,
        // t029: last emitted playout observation for change-dedup (call-owned, resets on peer replacement).
        var playoutObservation: JsonObject? = null,
        // t029: lifetime emission counters for the hard cap (retained across peer replacement).
        var playoutEmissions: Int = 0,
        var playoutCapNoticed: Boolean = false)
    private var login = LoginType.SIP_CREDENTIALS
    private var loginMethod: LoginMethod = LoginMethod.SipCredentials("")
    private var readyAttempt = -1.0
    private var socketAttempts = 0.0
    private var loginAttempts = 0.0
    private var loginRpcId: String? = null
    private var loginAt: Long? = null
    private var loginSucceededAt: Long? = null
    private var socketAt: Long? = null
    private var openedAt: Long? = null
    private var chainStartedAt: Long? = null
    private var firstSocketStartedAt: Long? = null
    private var loginChainAt: Long? = null
    private var appWaitMs: Double? = null
    private var emittedAppWait = false
    private var firstTimingsEmitted = false
    private var connectedOnce = false
    private var reconnect = false
    private var socketClosed = true
    private var attemptOpened = false
    private var attemptFailed = false
    private var previousGateway: GatewayState? = null
    private var previousNetwork: Pair<NetworkChangedPayloadNetworkType, Boolean>? = null
    // t027: last values confirmed via updateSignalingContext (client-owned across
    // reconnect logins; the server re-confirms them on the next CLIENT_READY).
    private var lastSignaledGeneration: Long? = null
    private var lastSignaledVoiceSdkId: String? = null
    private var audioObserver: V2AudioDeviceObserver? = null
    private var microphoneObserver: V2MicrophoneObserver? = null
    // t027: null = device observer has not delivered an inventory yet (count unknown);
    // empty = observer delivered and the inventory is known-empty (count is measured 0).
    private var deviceInventory: List<V2AudioDevice>? = null
    private var outputDevice: V2AudioDevice? = null
    private var appState: AppState? = null
    private data class GatewayCheck(val number: Double, val began: Long)
    private var gatewayChecks = mutableMapOf<String, GatewayCheck>()
    private var gatewayCheckCount = 0.0
    private val calls = mutableMapOf<V2CaptureToken, Capture>()
    private val observedPushOrigins = linkedSetOf<Pair<CallReportV2PushEvent.Fact, java.util.UUID>>()
    private val worker = scope.launch {
        for (command in ingress) {
            try {
                command()
            } catch (cancellation: CancellationException) {
                coroutineContext.ensureActive()
                dropped.incrementAndGet()
            } catch (_: Exception) {
                dropped.incrementAndGet()
            }
        }
    }
    private fun submit(action: suspend () -> Unit) {
        if (client.callReportV2DisabledByServer.get()) return

        if (ingress.trySend(action).isFailure) {
            dropped.incrementAndGet()
            // A dropped account-change fact must never leave the old account uploading new frames.
            if (ingressStopped.compareAndSet(false, true)) {
                com.telnyx.webrtc.sdk.utilities.Logger.w(message = "V2 native ingress overflow; reporting stopped until reconfiguration")
                detachLifecycle()
                ingress.cancel()
                scope.cancel()
            }
        }
    }
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lifecycleObserver: androidx.lifecycle.LifecycleEventObserver? = null
    private fun detachLifecycle() {
        mainHandler.post {
            audioObserver?.stop()
            audioObserver = null
            microphoneObserver?.stop()
            microphoneObserver = null
            lifecycleObserver?.let { androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(it) }
            lifecycleObserver = null
        }
    }
    /** True while this capture is still the active reporting owner for its client. */
    private fun stillCurrentOwner(): Boolean = client.callReportV2 === sink
    private val sink: CallReportV2Ingress = object : CallReportV2Ingress {
        override fun error(error: com.telnyx.webrtc.sdk.model.TelnyxError, token: V2CaptureToken?) {
            val timestamp = clock.wallTimestamp()
            submit {
                val call = token?.let { calls[it] }
                // A stale peer must not fall back to the currently active call or SDK scope.
                if (token != null && call == null) return@submit
                val info = ErrorInfo(error.name, error.message, error.code.toString())
                val coded = CodedErrorInfo(error.name, error.message, error.code.toString())
                val payload = when (TelnyxErrorCode.values().firstOrNull { it.code == error.code }) {
                    TelnyxErrorCode.MEDIA_MICROPHONE_PERMISSION_DENIED,
                    TelnyxErrorCode.MEDIA_DEVICE_NOT_FOUND,
                    TelnyxErrorCode.MEDIA_GET_USER_MEDIA_FAILED,
                    TelnyxErrorCode.UNABLE_READ_AUDIO_INPUT_ON_DEVICE_CHANGE -> ErrorPayload.Media(coded, error.fatal)
                    TelnyxErrorCode.SDP_CREATE_OFFER_FAILED,
                    TelnyxErrorCode.SDP_CREATE_ANSWER_FAILED,
                    TelnyxErrorCode.SDP_SET_LOCAL_DESCRIPTION_FAILED,
                    TelnyxErrorCode.SDP_SET_REMOTE_DESCRIPTION_FAILED,
                    TelnyxErrorCode.SDP_SEND_FAILED,
                    TelnyxErrorCode.HOLD_FAILED,
                    TelnyxErrorCode.INVALID_CALL_PARAMETERS,
                    TelnyxErrorCode.BYE_SEND_FAILED,
                    TelnyxErrorCode.SUBSCRIBE_FAILED,
                    TelnyxErrorCode.PEER_CLOSED_DURING_INIT,
                    TelnyxErrorCode.ICE_RESTART_FAILED,
                    TelnyxErrorCode.SESSION_NOT_REATTACHED -> ErrorPayload.Call(coded, error.fatal)
                    TelnyxErrorCode.GATEWAY_FAILED -> ErrorPayload.Gateway(info, error.fatal)
                    TelnyxErrorCode.LOGIN_FAILED,
                    TelnyxErrorCode.INVALID_CREDENTIALS,
                    TelnyxErrorCode.AUTHENTICATION_REQUIRED -> ErrorPayload.Login(info, error.fatal)
                    TelnyxErrorCode.WEBSOCKET_CONNECTION_FAILED,
                    TelnyxErrorCode.WEBSOCKET_ERROR,
                    TelnyxErrorCode.RECONNECTION_EXHAUSTED,
                    TelnyxErrorCode.NETWORK_OFFLINE -> ErrorPayload.Socket(info, error.fatal)
                    TelnyxErrorCode.UNEXPECTED_ERROR, null -> ErrorPayload.Sdk(info, error.fatal)
                }
                runtime.observe(EventPayload.Error(payload), timestamp, call?.lease, noActiveCall = token == null)
            }
        }
        override fun warning(warning: com.telnyx.webrtc.sdk.model.TelnyxWarning, token: V2CaptureToken?,
            measurement: V2WarningMeasurement?) {
            val timestamp = clock.wallTimestamp()
            submit {
                val call = token?.let { calls[it] }
                if (token != null && call == null) return@submit
                if (call != null) {
                    runtime.observe(EventPayload.CallWarning(CallWarningPayload(warning.code.toDouble(), warning.name,
                        metric = measurement?.metric, value = measurement?.value, threshold = measurement?.threshold)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
                } else {
                    // V2 has call_warning, not sdk_warning. Session warnings stay SDK-scoped logs.
                    runtime.observe(EventPayload.Logs(LogEntry(LogEntryLevel.WARN,
                        LogCategory.CONNECTION, warning.message,
                        buildJsonObject { put("code", warning.code); put("name", warning.name) })), timestamp, noActiveCall = true)
                }
            }
        }
        override suspend fun awaitDrain(timeoutMs: Long): Boolean {
            var synchronizedIngress = false
            return awaitV2TelemetryDrain(timeoutMs) {
                if (!synchronizedIngress) {
                    val barrier = CompletableDeferred<Unit>()
                    ingress.send { barrier.complete(Unit) }
                    barrier.await()
                    synchronizedIngress = true
                }
                runtime.snapshot().pendingCount
            }
        }
        override suspend fun capturedFrames(): List<String> = this@AndroidV2Capture.capturedFrames()

        override fun retarget(requested: CallReportV2Config, guard: () -> Boolean, onApplied: () -> Unit, onRejected: (Throwable) -> Unit) {
            val baseline = effectiveConfig
            if (requested.enabled != baseline.enabled || requested.captureOnly != baseline.captureOnly ||
                requested.mirrorConsole != baseline.mirrorConsole) {
                onRejected(IllegalArgumentException(
                    "Call Report V2 retargeting only supports environment or endpoint changes; " +
                        "the requested configuration also changes reporting mode"))
                return
            }
            if (!NATIVE_V2_RETARGET_SUPPORTED) {
                onRejected(UnsupportedOperationException(
                    "retargetBeforeFirstCredential is unavailable in the published observability runtime 0.2.3; " +
                        "environment or endpoint changes require candidate runtime capability"))
                return
            }
            if (client.callReportV2DisabledByServer.get()) {
                onRejected(IllegalStateException("telemetry was disabled by the server; retarget refused"))
                return
            }
            val delivered = ingress.trySend {
                if (!stillCurrentOwner()) return@trySend
                if (!guard()) {
                    onRejected(IllegalStateException(
                        "Call Report V2 retarget request superseded by a newer configuration"))
                    return@trySend
                }
                runRetarget(requested, onApplied, onRejected)
            }
            if (delivered.isFailure) {
                onRejected(IllegalStateException("Call Report V2 ingress is full; retarget request rejected"))
            }
        }

        private suspend fun runRetarget(requested: CallReportV2Config, onApplied: () -> Unit, onRejected: (Throwable) -> Unit) {
            val outcome = runCatching { retargetV2Runtime(runtime, requested) }
            if (outcome.isSuccess) {
                if (!stillCurrentOwner()) return
                effectiveConfig = requested
                onApplied()
                return
            }
            val failure = outcome.exceptionOrNull() ?: return
            if (failure is CancellationException) {
                coroutineContext.ensureActive()
                onRejected(failure)
                return
            }
            onRejected(failure)
        }
        override fun pushEvent(event: CallReportV2PushEvent) {
            val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date(event.observedAtEpochMs))
            submit {
                if (!observedPushOrigins.add(event.fact to event.originId)) return@submit
                if (observedPushOrigins.size > INGRESS_CAPACITY) observedPushOrigins.remove(observedPushOrigins.first())
                // Attach failures are final in the current flow (BYE follows); report them as errors,
                // not milestones. Milestones stay INFO. No other fact uses ERROR today.
                val level = when (event.fact) {
                    CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED -> LogEntryLevel.ERROR
                    else -> LogEntryLevel.INFO
                }
                runtime.observe(EventPayload.Logs(LogEntry(level, LogCategory.CONNECTION,
                    event.fact.name.lowercase(Locale.ROOT), buildJsonObject {
                        put("fact", event.fact.name.lowercase(Locale.ROOT)); put("fact_version", 1)
                        put("source", "android_native"); put("call_id", event.callId.toString())
                        put("origin_id", event.originId.toString()); put("observed_at_epoch_ms", event.observedAtEpochMs)
                        put("observed_at_monotonic_ms", event.observedAtMonotonicMs)
                        put("clock_domain", "android_elapsed_realtime")
                        event.action?.let { put("action", it.name.lowercase(Locale.ROOT)) }
                        event.messageKind?.let { put("message_kind", it.name.lowercase(Locale.ROOT)) }
                        event.reason?.let { put("reason", it.name.lowercase(Locale.ROOT)) }
                    })), timestamp, noActiveCall = true)
            }
        }
        override fun nativeFact(fact: V2NativeFact, token: V2CaptureToken?) {
            val (name, value) = when (fact) {
                is V2NativeFact.MicrophoneMuteSet -> "microphone_mute_set" to fact.muted.toString()
                is V2NativeFact.SpeakerphoneRequested -> "speakerphone_requested" to fact.enabled.toString()
                is V2NativeFact.AudioOutputRequested -> "audio_output_requested" to fact.device.name
                V2NativeFact.PushMetadataProcessed -> "push_metadata_processed" to "received_by_sdk"
                is V2NativeFact.MicrophonePermission -> "microphone_permission_observed" to fact.granted.toString()
                is V2NativeFact.MicrophoneSystemMute -> "microphone_system_mute_observed" to fact.muted.toString()
            }
            val timestamp = clock.wallTimestamp()
            submit {
                val call = token?.let { calls[it] }
                if (token != null && call == null) return@submit
                if (fact is V2NativeFact.MicrophoneMuteSet && call != null) {
                    runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(listOf(MediaChange.INPUT_DEVICE),
                        extra = buildJsonObject { put("microphone_muted_by_sdk", fact.muted) })), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
                }
                // Native-only facts use canonical logs with structured details, not invented V2 events.
                runtime.observe(EventPayload.Logs(LogEntry(LogEntryLevel.INFO, LogCategory.MEDIA,
                    name, buildJsonObject {
                        put("value", value); put("source", "android_sdk")
                        if (fact is V2NativeFact.MicrophonePermission || fact is V2NativeFact.MicrophoneSystemMute) {
                            put("fact", name); put("fact_version", 1); put("scope", "app")
                            put("source", "android_os")
                        }
                        if (fact is V2NativeFact.MicrophonePermission) put("granted", fact.granted)
                        if (fact is V2NativeFact.MicrophoneSystemMute) put("muted", fact.muted)
                    })),
                    timestamp, call?.lease, noActiveCall = token == null)
            }
        }
        override fun creationFinished(durationMs: Long) {
            val timestamp = clock.wallTimestamp()
            submit { runtime.observe(EventPayload.SdkCreated(SdkCreatedPayload(durationMs.coerceAtLeast(0).toDouble(),
                listOf(client.callReportV2InstanceId),
                extra = startupExtra())), timestamp, noActiveCall = true) }
        }
        override fun creationFailed(errorName: String) {
            val timestamp = clock.wallTimestamp()
            submit {
                try { runtime.observe(EventPayload.SdkCreationFailed(SdkCreationFailedPayload(
                    CodedErrorInfo(errorName.take(MAX_PROVIDER_ID_LENGTH), "Native SDK initialization failed", "SDK_INITIALIZATION_FAILED"))), timestamp, noActiveCall = true) }
                finally { try { runtime.shutdown() } finally { scope.cancel() } }
            }
            ingressStopped.set(true)
            if (client.callReportV2 === this) client.callReportV2 = null
            detachLifecycle()
            ingress.close()
        }
        override fun devices(devices: List<V2AudioDevice>) {
            val timestamp = clock.wallTimestamp()
            submit {
                val next = devices.distinctBy { it.id }.take(MAX_DEVICE_COUNT)
                fun listed(device: V2AudioDevice) = ListedDevice(device.id, device.label,
                    if (device.input) DeviceKind.AUDIOINPUT else DeviceKind.AUDIOOUTPUT)
                val before = deviceInventory.orEmpty().associateBy { it.id }
                val after = next.associateBy { it.id }
                if (before == after && deviceInventory != null) return@submit
                runtime.observe(EventPayload.DeviceListChanged(DeviceListChangedPayload(next.map(::listed),
                    next.filter { it.id !in before }.map(::listed), deviceInventory.orEmpty().filter { it.id !in after }.map(::listed))),
                    timestamp, noActiveCall = true)
                deviceInventory = next
                // t029: an inventory change re-measures both device-kind counts for every live
                // call even when the selected output did not change. A previously unknown
                // inventory (call count null) becomes measured here; the counts are the same
                // OS inventory for all of this client's calls, never a per-call guess.
                val measuredInput = next.count { it.input }.toDouble()
                val measuredOutput = next.count { !it.input }.toDouble()
                calls.values.forEach { call ->
                    call.measuredInputDeviceCount = measuredInput
                    call.measuredOutputDeviceCount = measuredOutput
                }
            }
        }
        /** t027: measured count or null when the device observer has never delivered an inventory. */
        private fun deviceCount(match: (V2AudioDevice) -> Boolean): Double? =
            deviceInventory?.count(match)?.toDouble()
        override fun outputDevice(device: V2AudioDevice) {
            val timestamp = clock.wallTimestamp()
            submit {
                if (outputDevice == device) return@submit
                outputDevice = device
                val measuredOutputCount = deviceCount { !it.input }
                calls.values.forEach { call -> call.measuredOutputDeviceCount = measuredOutputCount ?: call.measuredOutputDeviceCount }
                runtime.observe(EventPayload.OutputDeviceChanged(DeviceChangedPayload(DeviceInfo(device.id, device.label),
                    device_count = measuredOutputCount)), timestamp, noActiveCall = true)
                // AudioManager communication output is shared by this client's concurrent calls.
                calls.values.forEach { call -> runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(
                    listOf(MediaChange.OUTPUT_DEVICE), output_device_label = device.label,
                    output_device_count = measuredOutputCount)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId) }
            }
        }
        override fun socketFailed(message: String, willRetry: Boolean) {
            val timestamp = clock.wallTimestamp()
            submit {
                if (attemptOpened || attemptFailed) return@submit
                attemptFailed = true
                runtime.observe(EventPayload.SocketFailed(SocketFailedPayload(
                CodedErrorInfo("SocketError", message.take(MAX_DETAIL_LENGTH), "WEBSOCKET_CONNECTION_FAILED"), attempt = socketAttempts,
                will_retry = willRetry)), timestamp, noActiveCall = true) }
        }
        override fun gatewayCheckStarted(id: String) {
            val timestamp = clock.wallTimestamp(); val began = clock.monotonicMs()
            submit {
                if (id in gatewayChecks || gatewayChecks.size >= MAX_GATEWAY_CHECKS) return@submit
                val check = GatewayCheck(++gatewayCheckCount, began); gatewayChecks[id] = check
                runtime.observe(EventPayload.GatewayCheckStarted(GatewayCheckStartedPayload(check.number)), timestamp, noActiveCall = true)
                scope.launch {
                    delay(GATEWAY_CHECK_TIMEOUT_MS)
                    submit expire@ {
                        val expired = gatewayChecks.remove(id) ?: return@expire
                        runtime.observe(EventPayload.GatewayCheckFailed(GatewayCheckFailedPayload(expired.number,
                            ErrorInfo("GatewayCheckError", "Gateway check timed out"), client.socketFailureWillRetry())),
                            clock.wallTimestamp(), noActiveCall = true)
                    }
                }
            }
        }
        override fun gatewayCheckResult(id: String, state: String) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                val check = gatewayChecks.remove(id) ?: return@submit
                val mapped = GatewayState.values().firstOrNull { it.name == state } ?: GatewayState.UNKNOWN
                runtime.observe(EventPayload.GatewayCheckSucceeded(GatewayCheckSucceededPayload(check.number, mapped, state,
                    (observedAt - check.began).coerceAtLeast(0).toDouble())), timestamp, noActiveCall = true)
            }
        }
        override fun gatewayCheckFailed(id: String, message: String, willRetry: Boolean) {
            val timestamp = clock.wallTimestamp()
            submit {
                val check = gatewayChecks.remove(id) ?: return@submit
                runtime.observe(EventPayload.GatewayCheckFailed(GatewayCheckFailedPayload(check.number,
                    ErrorInfo("GatewayCheckError", message.take(MAX_DETAIL_LENGTH)), willRetry)), timestamp, noActiveCall = true)
            }
        }
        override fun providerIds(token: V2CaptureToken, legId: String?, sessionId: String?) = submit {
            calls[token]?.let { call ->
                if (!legId.isNullOrBlank()) call.legId = legId.take(MAX_PROVIDER_ID_LENGTH)
                if (!sessionId.isNullOrBlank()) call.providerSessionId = sessionId.take(MAX_PROVIDER_ID_LENGTH)
            }
        }
        override fun termination(token: V2CaptureToken, local: Boolean) = submit {
            calls[token]?.let { if (it.localEnd == null) it.localEnd = local }
        }
        override fun candidate(token: V2CaptureToken, sdp: String, remote: Boolean, ufrag: String?, signaled: Boolean?,
            serverUrl: String?, adapterType: PeerConnection.AdapterType?) {
            if (sdp.length > MAX_CANDIDATE_LENGTH) return
            val timestamp = clock.wallTimestamp()
            val observedAt = clock.monotonicMs()
            val observedUfrag = Regex("(?:^| )ufrag ([^ ]+)").find(sdp)?.groupValues?.get(1)?.take(MAX_DETAIL_LENGTH) ?: ufrag?.take(MAX_DETAIL_LENGTH)
            submit { calls[token]?.let { call ->
                updateIceGeneration(call, observedUfrag, remote)
                emitCandidate(call, sdp, remote, timestamp, signaled, observedAt, serverUrl, adapterType)
            } }
        }
        override fun description(token: V2CaptureToken, sdp: String, remote: Boolean) {
            if (sdp.length > MAX_SIGNALING_BYTES) return
            // Parse before enqueueing: do not retain SDP/credentials in the command queue.
            val ufrag = sdp.lineSequence().firstOrNull { it.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:")?.trim()?.take(MAX_DETAIL_LENGTH)
            val candidates = sdp.lineSequence().filter { it.startsWith("a=candidate:") }.take(MAX_CANDIDATES_PER_DESCRIPTION).toList()
            val timestamp = clock.wallTimestamp()
            val observedAt = clock.monotonicMs()
            submit {
                val call = calls[token] ?: return@submit
                updateIceGeneration(call, ufrag, remote)
                if (remote) call.remoteDescriptionAt = observedAt
                candidates.forEach { emitCandidate(call, it, remote, timestamp, observedAt = observedAt) }
            }
        }
        override fun inputTrack(token: V2CaptureToken, enabled: Boolean, ended: Boolean, muted: Boolean?) {
            val timestamp = clock.wallTimestamp()
            submit {
                val call = calls[token] ?: return@submit
                val input = muted?.let { InputDevice("microphone", enabled, it,
                    if (ended) InputDeviceReadyState.ENDED else InputDeviceReadyState.LIVE) }
                val measuredInputCount = deviceCount { it.input }
                call.measuredInputDeviceCount = measuredInputCount ?: call.measuredInputDeviceCount
                runtime.observe(EventPayload.InputDeviceChanged(DeviceChangedPayload(DeviceInfo("call-microphone", "microphone"),
                    device_count = measuredInputCount)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
                runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(listOf(MediaChange.INPUT_DEVICE), input_device = input,
                    input_device_count = measuredInputCount)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
        }
        override fun peerConfiguration(token: V2CaptureToken, configuration: V2PeerConfiguration) {
            val timestamp = clock.wallTimestamp()
            submit {
                val call = calls[token] ?: return@submit
                runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(emptyList(), extra = buildJsonObject {
                    put("changed", buildJsonArray { add("configuration") })
                    put("peer_configuration", buildJsonObject {
                        put("ice_transport_policy", if (configuration.relayOnly) "relay" else "all")
                        put("ice_candidate_pool_size", configuration.candidatePoolSize)
                        put("ice_server_count", configuration.iceServerCount)
                        put("sdp_semantics", "unified-plan"); put("bundle_policy", "max-compat")
                    })
                })), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
        }
        override fun peerMedia(token: V2CaptureToken, media: V2PeerMedia) {
            val timestamp = clock.wallTimestamp()
            submit {
                val call = calls[token] ?: return@submit
                val previous = call.peerMedia
                val merged = media.copy(sending = media.sending ?: previous?.sending, input = media.input ?: previous?.input,
                    extra = JsonObject(previous?.extra.orEmpty() + media.extra))
                if (merged == previous) return@submit
                call.peerMedia = merged
                val groups = mapOf("peer_state" to listOf("peer"),
                    "rtp_parameters" to listOf("send_parameters", "receive_parameters", "transceiver_direction", "transceiver_current_direction", "mid"),
                    "remote_track" to listOf("remote_track"), "input_device" to listOf("native_input_track"))
                    .filter { (_, keys) -> keys.any { merged.extra[it] != previous?.extra?.get(it) } }.keys
                val extra = buildJsonObject {
                    merged.extra.forEach { (key, value) -> put(key, value) }
                    if (groups.isNotEmpty()) put("changed", JsonArray(groups.map { JsonPrimitive(it) }))
                }
                runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(
                    changed = listOfNotNull(MediaChange.SENDING.takeIf { merged.sending != previous?.sending }, MediaChange.INPUT_DEVICE.takeIf { merged.input != previous?.input }),
                    sending = merged.sending, input_device = merged.input, extra = extra)), timestamp, call.lease,
                    telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
        }
        override fun peerState(token: V2CaptureToken, kind: String, state: String) {
            val timestamp = clock.wallTimestamp()
            val observedAt = clock.monotonicMs()
            submit {
                val call = calls[token] ?: return@submit
                if (kind == "ice_gathering_state" && state == "GATHERING") call.gatheringStartedAt = observedAt
                runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(emptyList(), extra = buildJsonObject {
                    put(kind, state.lowercase(Locale.ROOT))
                    put("changed", buildJsonArray { add(if (kind == "remote_track") "remote_track" else "peer_state") })
                })), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
        }
        override fun network(type: String, online: Boolean) {
            val timestamp = clock.wallTimestamp()
            submit {
                val mapped = NetworkChangedPayloadNetworkType.values().firstOrNull { it.name == type } ?: NetworkChangedPayloadNetworkType.UNKNOWN
                val current = mapped to online
                if (current == previousNetwork) return@submit
                runtime.observe(EventPayload.NetworkChanged(NetworkChangedPayload(previousNetwork == null, mapped, online)), timestamp, noActiveCall = true)
                previousNetwork = current
            }
        }
        override fun started(token: V2CaptureToken, callId: String, inbound: Boolean, reattach: Boolean, trickle: Boolean, options: V2CaptureOptions) {
            val timestamp = clock.wallTimestamp()
            val began = clock.monotonicMs()
            submit {
                if (calls.size >= MAX_ACTIVE_CALLS) { dropped.incrementAndGet(); return@submit }
                calls.entries.removeAll { it.value.lease.callId == callId }
                val capture = Capture(runtime.beginCall(callId), began, inbound = inbound)
                // t029: seed this call from the measured global device cache. The counts come from
                // the last delivered inventory (null = observer never delivered one, unknown);
                // the label comes from the latest known output selection, which is an OS-wide
                // communication-device fact shared by this client's calls — never inferred from
                // the inventory. Seeds persist into every later full media snapshot.
                capture.measuredInputDeviceCount = deviceCount { it.input } ?: capture.measuredInputDeviceCount
                capture.measuredOutputDeviceCount = deviceCount { !it.input } ?: capture.measuredOutputDeviceCount
                calls[token] = capture
                runtime.observe(EventPayload.CallStarted(CallStartedPayload(CallOptions(
                    direction = if (inbound) CallOptionsDirection.INBOUND else CallOptionsDirection.OUTBOUND,
                    caller_number = options.callerNumber?.takeIf { it.isNotBlank() },
                    caller_name = options.callerName?.takeIf { it.isNotBlank() },
                    destination_number = options.destinationNumber?.takeIf { it.isNotBlank() },
                    audio = BooleanOrConstraints.Enabled(true), video = BooleanOrConstraints.Enabled(false),
                    trickle_ice = trickle, force_relay_candidate = options.forceRelay, prefetch_ice_candidates = options.prefetch,
                    keep_connection_alive_on_socket_close = null, custom_ice_servers = options.customIce,
                    ice_servers_count = options.effectiveIceServerCount.toDouble(),
                    use_stereo = options.useStereo, use_sdp_as_bandwidth = null, remote_element_provided = null,
                    local_element_provided = null, mic_id_provided = false, speaker_id_provided = null,
                    camera_id_provided = null, is_reattach = reattach,
                    preferred_codecs = options.preferredCodecs?.takeIf { it.isNotEmpty() },
                    custom_header_names = options.customHeaderNames?.takeIf { it.isNotEmpty() }),
                    // t027: sanitized app-supplied call options (names only for custom headers;
                    // secrets/tokens/SDP are never in options — see FIELD_DELTA.csv).
                    options.rawCallOptions,
                    // t029: the configured server-URL count stays separate from the effective
                    // count in ice_servers_count (provenance, not the runtime peer state).
                    extra = buildJsonObject {
                        put("ice_servers_configured_count", options.configuredIceServerCount.toDouble())
                    })), timestamp, capture.lease)
            }
        }
        override fun state(token: V2CaptureToken, state: NativeCallState) {
            val timestamp = clock.wallTimestamp()
            val observedAt = clock.monotonicMs()
            submit {
                val call = calls[token] ?: return@submit
                if (state is NativeCallState.DONE || state is NativeCallState.ERROR || state is NativeCallState.DROPPED) {
                    if (call.terminal != null) return@submit
                    call.terminal = state
                    runtime.observe(EventPayload.CallState(CallStatePayload(CallState.HANGUP, call.state)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
                    // Teardown owns terminal capture, after the last observed stats snapshot.
                    return@submit
                }
                // Terminal teardown may race a delayed nonterminal callback.
                if (call.terminal != null) return@submit
                val mapped = when (state) {
                    NativeCallState.ACTIVE -> CallState.ACTIVE
                    NativeCallState.HELD -> CallState.HELD
                    NativeCallState.RINGING -> CallState.RINGING
                    is NativeCallState.RECONNECTING, NativeCallState.RENEGOTIATING -> CallState.RECOVERING
                    else -> CallState.REQUESTING
                }
                if (mapped == call.state) return@submit
                runtime.observe(EventPayload.CallState(CallStatePayload(mapped, call.state)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
                call.state = mapped
                if (mapped == CallState.ACTIVE) {
                    call.answered = true
                    if (call.answeredAt == null) call.answeredAt = observedAt
                    call.timings.putIfAbsent("call_active_ms", (observedAt - call.began).toDouble())
                    maybeEmitTimings(call, timestamp)
                }
            }
        }
        override fun collectsStats(): Boolean = !client.callReportV2DisabledByServer.get() && scope.isActive && !ingressStopped.get()
        override fun hasActiveCall(): Boolean = calls.isNotEmpty()
        override fun sendsToServerForLogin(): Boolean = config.enabled && !config.captureOnly && collectsStats()
        override fun sendsToServer(): Boolean = config.enabled && !config.captureOnly && !anonymousUpload &&
            collectsStats()
        override fun stats(token: V2CaptureToken, report: RTCStatsReport) {
            if (!collectsStats()) return
            val sampled = clock.monotonicMs()
            val timestamp = clock.wallTimestamp()
            val snapshot = extract(report)
            val media = V2NativeMedia.snapshot(report)
            submit {
                val call = calls[token] ?: return@submit
                observePlayout(call, report, timestamp)
                if ((snapshot.numbers["in.packetsReceived"] ?: 0.0) > 0) {
                    call.timings.putIfAbsent("first_packet_received_ms", (sampled - call.began).toDouble())
                }
                // t027: full media snapshots keep the latest measured device counts so the
                // report's latest row never loses them to a transient device-change event.
                // t029: the retained label is the latest known output selection — an OS-wide
                // communication-device fact shared by this client's calls, never inferred from
                // the device inventory. Unknown selection stays null.
                val mediaWithCounts = media.copy(
                    input_device_count = media.input_device_count ?: call.measuredInputDeviceCount,
                    output_device_count = media.output_device_count ?: call.measuredOutputDeviceCount,
                    output_device_label = media.output_device_label ?: outputDevice?.label)
                emitMedia(call, mediaWithCounts, timestamp, sampled, snapshot.pairId)
                val previous = call.lastSample
                if (previous != null && sampled - previous < SAMPLE_INTERVAL_MS) return@submit
                if (runtime.sampleStatsRecordedAt(call.lease, snapshot, (previous?.let { sampled - it } ?: SAMPLE_INTERVAL_MS).toDouble(), timestamp)) call.samples++
                call.lastSample = sampled
            }
        }
        override fun authenticate(type: String, identity: String?, secret: String?) {
            anonymousUpload = type == "anonymous"
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                login = when { type == "token" -> LoginType.TOKEN; type == "anonymous" -> LoginType.ANONYMOUS; identity?.startsWith("gencred") == true -> LoginType.GENCRED; else -> LoginType.SIP_CREDENTIALS }
                loginMethod = when (login) {
                    LoginType.TOKEN -> LoginMethod.Token()
                    LoginType.ANONYMOUS -> LoginMethod.Anonymous(secret ?: "unknown", identity ?: "unknown")
                    LoginType.GENCRED -> LoginMethod.Gencred(identity ?: "")
                    else -> LoginMethod.SipCredentials(identity ?: "")
                }
                loginRpcId = null
                loginAt = observedAt; loginSucceededAt = null; loginAttempts++
                if (loginAttempts == 1.0) loginChainAt = observedAt
                // t027: a reconnect login preserves this connection's generation and
                // this instance's voice_sdk_id (both client-owned; the next
                // CLIENT_READY re-confirms them). The server-issued session id is
                // cleared until the gateway re-registers, so metric samples stay
                // refused until the session is actually confirmed again.
                if (reconnect && login != LoginType.ANONYMOUS) {
                    runtime.updateSignalingContext(lastSignaledGeneration, lastSignaledVoiceSdkId, null, login)
                } else {
                    runtime.updateSignalingContext(null, null, null, login)
                }
                // Anonymous transitions clear prior account credentials before any new frames.
                val credentials = when (login) {
                    LoginType.ANONYMOUS -> null
                    LoginType.TOKEN -> buildJsonObject { put("login_token", secret ?: "") }
                    else -> buildJsonObject { put("login", identity ?: ""); put("passwd", secret ?: "") }
                }
                runtime.configure(credentials)
                runtime.resumeTelemetry()
                // t027: canonical resume_session_id is the sessid asked to resume — the
                // reconnect login re-sends the prior server session; a first login has
                // nothing to resume, so it stays null (never the freshly generated id).
                runtime.observe(EventPayload.LoginStarted(LoginStartedPayload(loginMethod, reconnect,
                    resume_session_id = if (reconnect) client.sessid.takeIf { it.isNotBlank() } else null)), timestamp, noActiveCall = true)
            }
        }
        override fun connectChainStarted() {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit { ensureChain(observedAt) }
        }
        override fun socketStarting(url: String, region: String?, resumeVoiceSdkId: String?) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                ensureChain(observedAt)
                if (firstSocketStartedAt == null) firstSocketStartedAt = observedAt
                socketAt = observedAt; socketAttempts++; socketClosed = false; attemptOpened = false; attemptFailed = false
                reconnect = connectedOnce
                runtime.observe(EventPayload.SocketConnectStarted(SocketConnectStartedPayload(url, url,
                    region = region, resume_voice_sdk_id = resumeVoiceSdkId?.takeIf { it.isNotBlank() },
                    use_canary_server = false,
                    skip_last_voice_sdk_id = false, skip_trailing = false, is_reconnect = reconnect)), timestamp, noActiveCall = true)
            }
        }
        override fun socketOpened(url: String, finalUrl: String, region: String?, resumeVoiceSdkId: String?) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                openedAt = observedAt; connectedOnce = true; socketClosed = false; attemptOpened = true
                runtime.observe(EventPayload.SocketConnected(SocketConnectedPayload(url, finalUrl,
                    region = region, resume_voice_sdk_id = resumeVoiceSdkId?.takeIf { it.isNotBlank() },
                    use_canary_server = false,
                    skip_last_voice_sdk_id = false, skip_trailing = false,
                    connect_duration_ms = (observedAt - (socketAt ?: observedAt)).toDouble())), timestamp, noActiveCall = true)
            }
        }
        override fun socketClosed(code: Int?, reason: String?, failed: Boolean, byClient: Boolean, willReconnect: Boolean?) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                if (socketClosed) return@submit
                socketClosed = true
                if (failed && !attemptOpened) return@submit
                runtime.observe(EventPayload.SocketClosed(SocketClosedPayload(code?.toDouble(), reason,
                    if (byClient) SocketClosedPayloadClosedBy.CLIENT else if (failed) SocketClosedPayloadClosedBy.NETWORK else SocketClosedPayloadClosedBy.SERVER,
                    (observedAt - (openedAt ?: observedAt)).toDouble(),
                    will_reconnect = willReconnect)), timestamp, noActiveCall = true)
            }
        }
        override fun signalingWriteStarted(text: String) {
            val id = loginRequestId(text) ?: return
            submit { loginRpcId = id }
        }
        override fun signalingWriteRejected(text: String) {
            val id = loginRequestId(text) ?: return
            submit { if (loginRpcId == id) loginRpcId = null }
        }
        override fun signalingFrame(text: String, outbound: Boolean) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_SIGNALING_BYTES) {
                dropped.incrementAndGet()
            } else {
                val raw = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
                if (raw != null) submit {
                    val id = (raw["id"] as? JsonPrimitive)?.contentOrNull
                    val result = raw["result"] as? JsonObject
                    // Authentication and gateway registration are separate boundaries.
                    // Only the response to the accepted current login RPC proves auth.
                    if (!outbound && id != null && id == loginRpcId &&
                        (result?.get("message") as? JsonPrimitive)?.contentOrNull == "logged in" &&
                        !raw.containsKey("error") && loginSucceededAt == null) {
                        loginRpcId = null
                        loginSucceededAt = observedAt
                        runtime.observe(EventPayload.LoginSucceeded(LoginSucceededPayload(loginMethod, reconnect,
                            (observedAt - (loginAt ?: observedAt)).coerceAtLeast(0).toDouble())), timestamp, noActiveCall = true)
                    }
                    runtime.observe(EventPayload.SignalingMessage(SignalingMessagePayload(
                    if (outbound) SignalingMessagePayloadDirection.SENT else SignalingMessagePayloadDirection.RECEIVED, raw)), timestamp) }
            }
        }
        override fun log(level: String, message: String) {
            val timestamp = clock.wallTimestamp()
            if (message.toByteArray(Charsets.UTF_8).size > MAX_SIGNALING_BYTES) { dropped.incrementAndGet(); return }
            val parsed = LogEntryLevel.values().firstOrNull { it.name.equals(level, ignoreCase = true) } ?: return
            submit { runtime.observe(EventPayload.Logs(LogEntry(parsed, LogCategory.GENERAL, message)), timestamp, noActiveCall = true) }
        }
        override fun rpcFailed(id: String?, code: Int?, message: String, willRetry: Boolean) {
            val timestamp = clock.wallTimestamp()
            submit {
                if (id == null || id != loginRpcId) return@submit
                loginRpcId = null
                runtime.observe(EventPayload.LoginFailed(LoginFailedPayload(loginMethod, reconnect,
                    CodedErrorInfo("LoginError", message.take(MAX_SIGNALING_BYTES), "LOGIN_FAILED", server_code = code?.toString()),
                    will_retry = willRetry)), timestamp, noActiveCall = true)
            }
        }
        override fun iceConnectionState(token: V2CaptureToken, state: String) {
            val timestamp = clock.wallTimestamp()
            val parsed = CallMediaChangedPayloadIceState.values().firstOrNull { it.name.equals(state, ignoreCase = true) } ?: return
            submit {
                val call = calls[token] ?: return@submit
                runtime.observe(EventPayload.CallMediaChanged(CallMediaChangedPayload(
                    changed = listOf(MediaChange.ICE_STATE), ice_state = parsed)), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
        }
        override fun appState(background: Boolean) {
            val timestamp = clock.wallTimestamp()
            submit {
                val next = if (background) AppState.BACKGROUND else AppState.FOREGROUND
                if (appState != next) {
                    runtime.observe(EventPayload.AppStateChanged(AppStateChangedPayload(next, appState)), timestamp, noActiveCall = true)
                    appState = next
                }
            }
        }
        override fun ready() {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                // JS parity: readiness is only reported for a matching successful
                // login; duplicates for the same attempt are suppressed.
                if (loginAt == null || loginSucceededAt == null || readyAttempt == loginAttempts) return@submit
                readyAttempt = loginAttempts
                val loginStart = loginChainAt ?: loginAt
                val elapsed = loginSucceededAt?.let { (it - (loginStart ?: it)).coerceAtLeast(0).toDouble() }
                runtime.observe(EventPayload.ClientReady(ClientReadyPayload(reconnect, null, false, null,
                    calls.values.map { it.lease.callId })), timestamp, noActiveCall = true)
                runtime.observe(EventPayload.SessionTimings(SessionTimingsPayload(reconnect,
                    (observedAt - client.callReportV2CreatedAtMs).toDouble(),
                    chainStartedAt?.let { (observedAt - it).toDouble() } ?: (observedAt - (socketAt ?: observedAt)).toDouble(),
                    app_wait_ms = if (!firstTimingsEmitted) appWaitMs else null,
                    socket_connect_ms = openedAt?.let { opened ->
                        (firstSocketStartedAt ?: socketAt)?.let { (opened - it).toDouble() } },
                    login_ms = elapsed,
                    login_to_ready_ms = loginSucceededAt?.let { (observedAt - it).coerceAtLeast(0).toDouble() },
                    socket_attempts = socketAttempts, login_attempts = loginAttempts)), timestamp, noActiveCall = true)
                firstTimingsEmitted = true
                chainStartedAt = null
            }
        }
        override fun gateway(state: String) {
            val timestamp = clock.wallTimestamp()
            submit {
                val mapped = GatewayState.values().firstOrNull { it.name == state } ?: GatewayState.UNKNOWN
                runtime.observe(EventPayload.GatewayState(GatewayStatePayload(mapped, state, previousGateway)), timestamp, noActiveCall = true)
                previousGateway = mapped
            }
        }
        override fun timing(token: V2CaptureToken, field: String) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                val call = calls[token] ?: return@submit
                call.timings.putIfAbsent(field, (observedAt - call.began).toDouble())
                maybeEmitTimings(call, timestamp)
            }
        }
        override fun suspendTelemetry() = submit { runtime.suspendTelemetry() }
        override fun isActive(): Boolean = scope.isActive
        override fun dispose() {
            ingressStopped.set(true)
            if (client.callReportV2 === this) client.callReportV2 = null
            detachLifecycle()
            ingress.cancel()
            // Immediate revocation: do not drain already queued old-account observations.
            scope.cancel()
        }
        override fun loginType(type: String) = submit {
            login = when (type) { "token" -> LoginType.TOKEN; "anonymous" -> LoginType.ANONYMOUS; else -> LoginType.SIP_CREDENTIALS }
            runtime.updateSignalingContext(null, null, null, login)
        }
        override fun signaling(generation: Long, voiceSdkId: String?, sessionId: String?) = submit {
            if (generation > 0) lastSignaledGeneration = generation
            voiceSdkId?.takeIf { it.isNotBlank() }?.let { lastSignaledVoiceSdkId = it }
            runtime.updateSignalingContext(generation.takeIf { it > 0 }, voiceSdkId,
                sessionId?.takeIf { it.isNotBlank() }, login)
        }
        /** JS parity (session.ts _ensureChain): a chain starts once, before the first
         * socket attempt; retries before ready() extend the SAME chain. */
        private fun ensureChain(observedAt: Long) {
            if (chainStartedAt != null) return
            socketAttempts = 0.0
            loginAttempts = 0.0
            readyAttempt = -1.0
            chainStartedAt = observedAt
            firstSocketStartedAt = null
            openedAt = null
            loginChainAt = null
            loginAt = null
            loginSucceededAt = null
            if (!emittedAppWait) {
                emittedAppWait = true
                appWaitMs = (observedAt - client.callReportV2CreatedAtMs).coerceAtLeast(0).toDouble()
            }
        }
        override fun peerClosing(token: V2CaptureToken, report: RTCStatsReport?) {
            val closedAt = clock.monotonicMs()
            val timestamp = clock.wallTimestamp()
            val finalSnapshot = report?.let { extract(it) }
            submit {
            val call = calls[token] ?: return@submit
            // Reconnection disposes a Peer without ending the Call. Reset only the stats baseline.
            if (call.terminal == null) {
                runtime.peerReplaced(call.lease)
                call.iceGeneration++; call.remoteIceGeneration++
                call.localUfrag = null; call.remoteUfrag = null; call.candidates.clear()
                call.gatheringStartedAt = null; call.remoteDescriptionAt = null
                call.media = null; call.mediaPairId = null; call.peerMedia = null
                call.playoutObservation = null
                return@submit
            }
            calls.remove(token)
            if (finalSnapshot != null) {
                if (runtime.sampleStatsRecordedAt(call.lease, finalSnapshot,
                    (call.lastSample?.let { closedAt - it } ?: SAMPLE_INTERVAL_MS).coerceAtLeast(1).toDouble(), timestamp)) call.samples++
            }
            val totals = runtime.callTotals(call.lease) ?: return@submit
            val termination = (call.terminal as? NativeCallState.DONE)?.reason
            val reason = when {
                call.terminal is NativeCallState.DROPPED -> CallEndReason.NETWORK_LOST
                call.terminal is NativeCallState.ERROR -> CallEndReason.FAILED
                call.localEnd == true && call.answered -> CallEndReason.LOCAL_HANGUP
                call.localEnd == true -> if (call.inbound) CallEndReason.REJECTED else CallEndReason.CANCELLED
                call.localEnd == false && call.answered -> CallEndReason.REMOTE_HANGUP
                call.localEnd == false && (termination?.cause in listOf("USER_BUSY", "CALL_REJECTED_BUSY") || termination?.sipCode in BUSY_SIP_CODES) -> CallEndReason.BUSY
                call.localEnd == false && (termination?.cause in listOf("NO_ANSWER", "NO_USER_RESPONSE", "ORIGINATOR_CANCEL_TIMEOUT") || termination?.sipCode in NO_ANSWER_SIP_CODES) -> CallEndReason.NO_ANSWER
                call.localEnd == false -> CallEndReason.REMOTE_HANGUP
                else -> CallEndReason.UNKNOWN
            }
            emitTimings(call, timestamp, complete = false)
            runtime.observe(EventPayload.CallEnded(CallEndedPayload(reason, cause = termination?.cause,
                cause_code = termination?.causeCode?.toDouble(), sip_code = termination?.sipCode?.toDouble(), sip_reason = termination?.sipReason, last_state = call.state,
                answered = call.answered, duration_ms = (closedAt - call.began).toDouble(),
                talk_ms = call.answeredAt?.let { (closedAt - it).toDouble() }, metrics_samples = call.samples.toDouble(), totals = totals.toCallTotals(),
                extra = buildJsonObject { put("final_stats_source", if (finalSnapshot != null) "before_peer_disposal" else "last_observed_snapshot_timeout") })), timestamp, lease = call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
        }
        }
    }
    init {
        synchronized(client) {
            if (client.callReportV2 != null) {
                scope.cancel()
                error("V2 capture already installed for this client")
            }
            client.callReportV2 = sink
        }
        val timestamp = clock.wallTimestamp()
        if (!suppressCreationEvents) {
            if (constructing) submit {
                // t027: SDK options as they exist at construction time. The login
                // configuration (region, custom ICE servers, push provider, log
                // level) is applied later by connect()/login(); values that are not
                // known yet stay null/empty rather than being invented. Mapping
                // lives in v2SdkOptions() so it cannot drift from the derivation
                // shared with call_started options.
                val options = v2SdkOptions()
                runtime.observe(EventPayload.SdkCreationStarted(SdkCreationStartedPayload(
                    SdkOptions(login = options.login, debug = false, login_type = SdkOptionsLoginType.SIP_CREDENTIALS,
                        explicit_rtc_provided = false, use_canary = null, skip_trailing = false,
                        region = options.region,
                        keep_connection_alive_on_socket_close = null, hangup_on_before_unload = null,
                        trickle_ice = client.getUseTrickleIce(), prefetch_ice_candidates = client.prefetchIceCandidates,
                        force_relay_candidate = client.getForceRelayCandidate(), muted_mic_on_start = null,
                        push_when_active = null, early_sdp_answer = null, media_permissions_recovery = null,
                        ice_servers = options.iceServers, custom_ice_servers = options.customIceServers,
                        push_provider = SdkOptionsPushProvider.NONE, log_level = options.logLevel,
                        telemetry_enabled = config.enabled), buildJsonObject {},
                    extra = startupExtra(buildJsonObject {
                        put("options_phase", "constructor_before_login_configuration")
                    })
                )), timestamp, noActiveCall = true)
            } else sink.creationFinished(client.callReportV2CreationDurationMs)
        }
        if (!BuildConfig.IS_TESTING.get()) client.observeV2Network()
        if (!BuildConfig.IS_TESTING.get()) mainHandler.post {
            if (!ingressStopped.get() && scope.isActive && !client.callReportV2DisabledByServer.get()) {
                val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                    when (event) {
                        androidx.lifecycle.Lifecycle.Event.ON_START -> {
                            microphoneObserver?.refreshPermission()
                            sink.appState(false)
                        }
                        androidx.lifecycle.Lifecycle.Event.ON_STOP -> sink.appState(true)
                        else -> Unit
                    }
                }
                audioObserver = V2AudioDeviceObserver(client.context, sink).also { it.start() }
                microphoneObserver = V2MicrophoneObserver(client.context, sink).also { it.start() }
                lifecycleObserver = observer
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(observer)
            }
        }
    }
    val droppedIngress: Long get() = dropped.get()

    /**
     * t027: SDK options as they exist at construction time. Only construction-time
     * state is read (no credentials, no tokens, no SDP); login-applied values
     * (region, custom ICE servers, push provider, log level) are unknown until
     * connect()/login() runs and stay at their honest null/empty defaults.
     */
    private fun v2SdkOptions(): SdkOptionsSnapshot {
        val servers = listOfNotNull(client.providedTurn, client.providedStun)
            .filter { it.isNotBlank() }
            .map { IceServerInfo(V2NativeMedia.stripUrlCredentials(it.trim()), has_credential = false) }
            .distinctBy { it.url }
        return SdkOptionsSnapshot(
            login = null, // login configuration is applied by a later authenticate(); null is honest here
            region = client.v2RegionHint,
            iceServers = servers,
            customIceServers = client.providedTurn != null || client.providedStun != null,
            logLevel = mapToSdkLogLevel(Logger.currentLevel),
        )
    }
    private data class SdkOptionsSnapshot(
        val login: String?, val region: String?, val iceServers: List<IceServerInfo>,
        val customIceServers: Boolean, val logLevel: SdkOptionsLogLevel,
    )

    /** Maps the SDK LogLevel to the V2 contract enum; unknown values map to OFF. */
    private fun mapToSdkLogLevel(level: com.telnyx.webrtc.sdk.model.LogLevel?): SdkOptionsLogLevel = when (level) {
        com.telnyx.webrtc.sdk.model.LogLevel.DEBUG -> SdkOptionsLogLevel.DEBUG
        com.telnyx.webrtc.sdk.model.LogLevel.INFO -> SdkOptionsLogLevel.INFO
        com.telnyx.webrtc.sdk.model.LogLevel.WARNING -> SdkOptionsLogLevel.WARN
        com.telnyx.webrtc.sdk.model.LogLevel.ERROR -> SdkOptionsLogLevel.ERROR
        else -> SdkOptionsLogLevel.OFF
    }

    suspend fun capturedFrames(): List<String> {
        val barrier = CompletableDeferred<Unit>()
        ingress.send { barrier.complete(Unit) }
        barrier.await()
        return runtime.capturedFrames()
    }
    suspend fun shutdown() {
        if (client.callReportV2 === sink) client.callReportV2 = null
        ingressStopped.set(true)
        detachLifecycle()
        ingress.close()
        try {
            worker.join()
            if (scope.isActive) runtime.shutdown()
        } catch (cancelled: CancellationException) {
            // dispose/reconfiguration may already have cancelled this reporting owner.
            // Do not swallow cancellation of the caller itself.
            currentCoroutineContext().ensureActive()
            if (scope.isActive) throw cancelled
        } finally { scope.cancel() }
    }
    private fun updateIceGeneration(call: Capture, ufrag: String?, remote: Boolean) {
        if (ufrag == null) return
        val previous = if (remote) call.remoteUfrag else call.localUfrag
        if (previous != null && previous != ufrag) {
            if (remote) {
                call.remoteIceGeneration++
                // The stale remote-description anchor must not measure a newer generation.
                call.remoteDescriptionAt = null
            } else call.iceGeneration++
            call.candidates.removeAll { it.startsWith(if (remote) "remote:" else "local:") }
        }
        if (remote) call.remoteUfrag = ufrag else call.localUfrag = ufrag
    }
    private suspend fun emitCandidate(call: Capture, sdp: String, remote: Boolean, timestamp: String,
        signaled: Boolean? = null, observedAt: Long? = null, serverUrl: String? = null,
        adapterType: PeerConnection.AdapterType? = null) {
        val parsed = V2NativeMedia.candidate(sdp, remote, if (remote) call.remoteIceGeneration else call.iceGeneration,
            serverUrl, adapterType) ?: return
        // Local: time since the observed gathering start; remote: since the remote description arrived.
        val sinceMs = if (remote) call.remoteDescriptionAt?.let { observedAt?.minus(it) }
        else call.gatheringStartedAt?.let { observedAt?.minus(it) }
        // Preserve the first observation only: never invent a second false→true candidate occurrence.
        val candidate = parsed.copy(signaled = signaled.takeUnless { remote },
            since_gathering_started_ms = sinceMs?.toDouble()?.takeIf { it >= 0 && it.isFinite() })
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(sdp.removePrefix("a=").trim().split(Regex("\\s+")).take(V2NativeMedia.CANDIDATE_CORE_FIELDS).joinToString(" ").toByteArray())
            .joinToString("") { "%02x".format(it) }
        val key = (if (remote) "remote:" else "local:") + fingerprint
        if (call.candidates.size >= MAX_CANDIDATES_PER_CALL || !call.candidates.add(key)) return
        runtime.observe(EventPayload.IceCandidate(candidate), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
    }
    private suspend fun emitMedia(call: Capture, media: CallMediaChangedPayload, timestamp: String, observedAt: Long, pairId: String?) {
        val previous = call.media
        val changed = V2NativeMedia.changes(media, previous, pairId != null && pairId != call.mediaPairId).toMutableList()
        // t029: retained device facts participate in full-snapshot change detection so a
        // measured count/label update is never swallowed by snapshot dedup, and the marker
        // keeps the existing contract vocabulary (input_device / output_device).
        val outputFactsChanged = media.output_device_label != previous?.output_device_label ||
            (media.output_device_count != null && media.output_device_count != previous?.output_device_count)
        val inputCountChanged = media.input_device_count != null && media.input_device_count != previous?.input_device_count
        if (outputFactsChanged) changed.add(MediaChange.OUTPUT_DEVICE)
        if (inputCountChanged) changed.add(MediaChange.INPUT_DEVICE)
        val extraChanges = V2NativeMedia.extraChanges(media, previous)
        val extra = if (extraChanges.isEmpty()) media.extra else buildJsonObject {
            media.extra?.forEach { (key, value) -> put(key, value) }
            put("changed", JsonArray(extraChanges.map { JsonPrimitive(it) }))
        }
        if (changed.isNotEmpty() || extraChanges.isNotEmpty()) runtime.observe(EventPayload.CallMediaChanged(media.copy(changed = changed, extra = extra,
            previous_local_candidate = previous?.local_candidate.takeIf { MediaChange.CANDIDATE_PAIR in changed },
            previous_remote_candidate = previous?.remote_candidate.takeIf { MediaChange.CANDIDATE_PAIR in changed })), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
        if (pairId != null) call.mediaPairId = pairId
        call.media = media.copy(codec_in = media.codec_in ?: previous?.codec_in, codec_out = media.codec_out ?: previous?.codec_out,
            local_candidate = media.local_candidate ?: previous?.local_candidate, remote_candidate = media.remote_candidate ?: previous?.remote_candidate,
            dtls_state = media.dtls_state ?: previous?.dtls_state,
            input_device_count = media.input_device_count ?: previous?.input_device_count,
            output_device_count = media.output_device_count ?: previous?.output_device_count,
            output_device_label = media.output_device_label ?: previous?.output_device_label)
        if (media.dtls_state == CallMediaChangedPayloadDtlsState.CONNECTED) {
            call.timings.putIfAbsent("dtls_connected_ms", (observedAt - call.began).toDouble())
            maybeEmitTimings(call, timestamp)
        }
    }
    private suspend fun maybeEmitTimings(call: Capture, timestamp: String) {
        if (call.answered && "dtls_connected_ms" in call.timings) emitTimings(call, timestamp, true)
    }
    private suspend fun emitTimings(call: Capture, timestamp: String, complete: Boolean) {
        if (call.timingsSent) return
        call.timingsSent = true
        val json = buildJsonObject { put("complete", complete); call.timings.forEach { (field, elapsed) -> put(field, elapsed) } }
        val payload = V2Json.codec.decodeFromJsonElement(CallTimingsPayload.serializer(), json)
        runtime.observe(EventPayload.CallTimings(payload), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
    }
    private fun loginRequestId(text: String): String? {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SIGNALING_BYTES) return null
        val raw = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
        val method = (raw?.get("method") as? JsonPrimitive)?.contentOrNull
        return if (method in listOf("login", "telnyx_rtc.login", "anonymous_login", "telnyx_rtc.anonymous_login"))
            (raw?.get("id") as? JsonPrimitive)?.contentOrNull else null
    }
    /**
     * t029: startup provenance under the existing payload `extra`. Facts are read on the
     * reporting worker (never blocking construction) and only when actually readable;
     * anything unavailable is omitted rather than guessed.
     */
    private fun startupExtra(base: JsonObject? = null): JsonObject = buildJsonObject {
        base?.forEach { (key, value) -> put(key, value) }
        put("startup", (startupFactsOverride ?: V2StartupFactsReader.read(client.context)).toJson())
    }

    /**
     * t029: bounded, change-deduped, call-owned playout observation. Emits only when the
     * observation changes, so there is no per-second flood; a reconnected peer starts a
     * fresh observation instead of carrying over the previous peer's state.
     */
    private suspend fun observePlayout(call: Capture, report: RTCStatsReport, timestamp: String) {
        val observation = V2PlayoutDiagnostic.observe(report)
        if (observation == call.playoutObservation) return
        call.playoutObservation = observation
        if (call.playoutEmissions >= PLAYOUT_OBSERVATION_CAP) {
            if (!call.playoutCapNoticed) {
                call.playoutCapNoticed = true
                runtime.observe(EventPayload.Logs(LogEntry(LogEntryLevel.INFO, LogCategory.MEDIA,
                    "playout_observation_capped", details = buildJsonObject {
                        put("cap", PLAYOUT_OBSERVATION_CAP.toDouble())
                        put("emitted", call.playoutEmissions.toDouble())
                    })), timestamp, call.lease, telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
            }
            return
        }
        call.playoutEmissions++
        runtime.observe(EventPayload.Logs(LogEntry(LogEntryLevel.INFO, LogCategory.MEDIA,
            "playout_stats_observation", details = observation)), timestamp, call.lease,
            telnyxLegId = call.legId, telnyxSessionId = call.providerSessionId)
    }

    private fun reportPrefix(stat: com.telnyx.webrtc.lib.RTCStats, selectedPair: String?): String? = when {
        stat.type == "candidate-pair" -> "pair".takeIf { stat.id == selectedPair }
        stat.type == "transport" -> "tr"
        stat.members["kind"] != "audio" && stat.members["mediaType"] != "audio" -> null
        else -> mapOf("media-playout" to "play", "media-source" to "src", "inbound-rtp" to "in",
            "outbound-rtp" to "out", "remote-inbound-rtp" to "rin", "remote-outbound-rtp" to "rout")[stat.type]
    }
    private fun extract(report: RTCStatsReport): V2StatsSnapshot {
        val numbers = mutableMapOf<String, Double>()
        var pairId: String? = null
        val reports = report.statsMap.values
        val selectedPair = (reports.firstOrNull { it.type == "transport" && it.members["selectedCandidatePairId"] is String }?.members?.get("selectedCandidatePairId") as? String)
            ?: reports.firstOrNull { it.type == "candidate-pair" && it.members["selected"] == true }?.id
            ?: reports.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }?.id
        for (stat in reports) {
            val prefix = reportPrefix(stat, selectedPair) ?: continue
            if (prefix == "pair") pairId = stat.id
            stat.members.forEach { (key, value) ->
                val number = (value as? Number)?.toDouble()
                if (number != null && number.isFinite()) numbers["$prefix.$key"] = number
            }
        }
        return V2StatsSnapshot(numbers, pairId, reports.firstOrNull { it.type == "transport" }?.members
            ?.get("selectedCandidatePairChanges")?.let { (it as? Number)?.toDouble()?.takeIf { value -> value.isFinite() } })
    }
}
