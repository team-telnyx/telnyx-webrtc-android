package com.telnyx.webrtc.sdk.observability

import android.os.Build
import android.os.SystemClock
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState as NativeCallState
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.*
import com.telnyx.webrtc.mobile.shared.observability.core.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong

/** Thin native integration. Shared runtime owns schemas, sequencing, metrics and independent transport. */
class AndroidV2Capture(private val client: TelnyxClient,
    private val config: CallReportV2Config = CallReportV2Config(captureOnly = true, mirrorConsole = false)) {
    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val INGRESS_CAPACITY = 256
        const val MAX_ACTIVE_CALLS = 128
        const val SAMPLE_INTERVAL_MS = 1000L
        const val MAX_SIGNALING_BYTES = 64 * 1024
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = object : V2Clock {
        override fun monotonicMs() = System.nanoTime() / NANOS_PER_MS
        override fun wallTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
    private val runtime = V2Runtime(scope, client.callReportV2InstanceId,
        ClientInfo(if (config.development) ClientInfoEnvironment.DEVELOPMENT else ClientInfoEnvironment.PRODUCTION, ClientInfoSdk.ANDROID, BuildConfig.SDK_VERSION,
            ClientInfoOs.ANDROID, Build.VERSION.RELEASE, "Android-" + BuildConfig.SDK_VERSION),
        LoginType.SIP_CREDENTIALS, config.localEndpoint ?: V2Endpoints.forEnvironment(
            if (config.development) ClientInfoEnvironment.DEVELOPMENT else ClientInfoEnvironment.PRODUCTION), clock,
        com.telnyx.webrtc.mobile.shared.observability.transport.ws.OkHttpV2SocketFactory(),
        captureOnly = config.captureOnly, enabled = config.enabled,
        onFrame = if (config.mirrorConsole) ({ frame -> println(frame) }) else null,
        sequenceProvider = client::nextCallReportV2Sequence)
    private val ingress = Channel<suspend () -> Unit>(INGRESS_CAPACITY)
    private val dropped = AtomicLong()
    private val ingressStopped = java.util.concurrent.atomic.AtomicBoolean(false)
    private data class Capture(val lease: V2CallLease, val began: Long, var state: CallState = CallState.NEW,
        var answered: Boolean = false, var samples: Int = 0, var lastSample: Long? = null,
        var terminal: NativeCallState? = null, var answeredAt: Long? = null,
        val timings: MutableMap<String, Double> = mutableMapOf())
    private var login = LoginType.SIP_CREDENTIALS
    private var loginMethod: LoginMethod = LoginMethod.SipCredentials("")
    private var readyAttempt = -1.0
    private var socketAttempts = 0.0
    private var loginAttempts = 0.0
    private var loginAt: Long? = null
    private var socketAt: Long? = null
    private var openedAt: Long? = null
    private var connectedOnce = false
    private var reconnect = false
    private var socketClosed = true
    private var previousGateway: GatewayState? = null
    private val calls = mutableMapOf<V2CaptureToken, Capture>()
    private val worker = scope.launch {
        for (command in ingress) try { command() } catch (_: Exception) { dropped.incrementAndGet() }
    }
    private fun submit(action: suspend () -> Unit) {
        if (ingress.trySend(action).isFailure) {
            dropped.incrementAndGet()
            // A dropped account-change fact must never leave the old account uploading new frames.
            if (ingressStopped.compareAndSet(false, true)) {
                com.telnyx.webrtc.sdk.utilities.Logger.w(message = "V2 native ingress overflow; reporting stopped until reconfiguration")
                ingress.cancel()
                scope.cancel()
            }
        }
    }
    private val sink: CallReportV2Ingress = object : CallReportV2Ingress {
        override fun started(token: V2CaptureToken, callId: String, inbound: Boolean, reattach: Boolean, trickle: Boolean, options: V2CaptureOptions) {
            val timestamp = clock.wallTimestamp()
            val began = clock.monotonicMs()
            submit {
                if (calls.size >= MAX_ACTIVE_CALLS) { dropped.incrementAndGet(); return@submit }
                calls.entries.removeAll { it.value.lease.callId == callId }
                val capture = Capture(runtime.beginCall(callId), began)
                calls[token] = capture
                runtime.observe(EventPayload.CallStarted(CallStartedPayload(CallOptions(
                    direction = if (inbound) CallOptionsDirection.INBOUND else CallOptionsDirection.OUTBOUND,
                    audio = BooleanOrConstraints.Enabled(true), video = BooleanOrConstraints.Enabled(false),
                    trickle_ice = trickle, force_relay_candidate = options.forceRelay, prefetch_ice_candidates = options.prefetch,
                    keep_connection_alive_on_socket_close = null, custom_ice_servers = options.customIce, ice_servers_count = options.iceServerCount.toDouble(),
                    use_stereo = null, use_sdp_as_bandwidth = null, remote_element_provided = null,
                    local_element_provided = null, mic_id_provided = false, speaker_id_provided = null,
                    camera_id_provided = null, is_reattach = reattach), buildJsonObject {})), timestamp, capture.lease)
            }
        }
        override fun state(token: V2CaptureToken, state: NativeCallState) {
            val timestamp = clock.wallTimestamp()
            val observedAt = clock.monotonicMs()
            submit {
                val call = calls[token] ?: return@submit
                if (state is NativeCallState.DONE || state is NativeCallState.ERROR || state is NativeCallState.DROPPED) {
                    call.terminal = state
                    // Teardown owns terminal capture, after the last observed stats snapshot.
                    return@submit
                }
                val mapped = when (state) {
                    NativeCallState.ACTIVE -> CallState.ACTIVE
                    NativeCallState.HELD -> CallState.HELD
                    NativeCallState.RINGING -> CallState.RINGING
                    is NativeCallState.RECONNECTING, NativeCallState.RENEGOTIATING -> CallState.RECOVERING
                    else -> CallState.REQUESTING
                }
                runtime.observe(EventPayload.CallState(CallStatePayload(mapped, call.state)), timestamp, call.lease)
                call.state = mapped
                if (mapped == CallState.ACTIVE) {
                    call.answered = true
                    if (call.answeredAt == null) call.answeredAt = observedAt
                    call.timings.putIfAbsent("call_active_ms", (observedAt - call.began).toDouble())
                    emitTimings(call, timestamp, complete = false)
                }
            }
        }
        override fun stats(token: V2CaptureToken, report: RTCStatsReport) {
            val sampled = clock.monotonicMs()
            val timestamp = clock.wallTimestamp()
            val snapshot = extract(report)
            submit {
                val call = calls[token] ?: return@submit
                val previous = call.lastSample
                if (previous != null && sampled - previous < SAMPLE_INTERVAL_MS) return@submit
                runtime.sampleStatsAt(call.lease, snapshot, (previous?.let { sampled - it } ?: SAMPLE_INTERVAL_MS).toDouble(), timestamp)
                call.samples++; call.lastSample = sampled
            }
        }
        override fun authenticate(type: String, identity: String?, secret: String?) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                login = when { type == "token" -> LoginType.TOKEN; type == "anonymous" -> LoginType.ANONYMOUS; identity?.startsWith("gencred") == true -> LoginType.GENCRED; else -> LoginType.SIP_CREDENTIALS }
                loginMethod = when (login) {
                    LoginType.TOKEN -> LoginMethod.Token()
                    LoginType.ANONYMOUS -> LoginMethod.Anonymous(secret ?: "unknown", identity ?: "unknown")
                    LoginType.GENCRED -> LoginMethod.Gencred(identity ?: "")
                    else -> LoginMethod.SipCredentials(identity ?: "")
                }
                loginAt = observedAt; loginAttempts++
                runtime.updateSignalingContext(null, null, null, login)
                // Anonymous transitions clear prior account credentials before any new frames.
                val credentials = when (login) {
                    LoginType.ANONYMOUS -> null
                    LoginType.TOKEN -> buildJsonObject { put("login_token", secret ?: "") }
                    else -> buildJsonObject { put("login", identity ?: ""); put("passwd", secret ?: "") }
                }
                runtime.configure(credentials)
                runtime.resumeTelemetry()
                runtime.observe(EventPayload.LoginStarted(LoginStartedPayload(loginMethod, reconnect)), timestamp, noActiveCall = true)
            }
        }
        override fun socketStarting(url: String) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                socketAt = observedAt; socketAttempts++; socketClosed = false
                reconnect = connectedOnce
                runtime.observe(EventPayload.SocketConnectStarted(SocketConnectStartedPayload(url, url, use_canary_server = false,
                    skip_last_voice_sdk_id = false, skip_trailing = false, is_reconnect = reconnect)), timestamp, noActiveCall = true)
            }
        }
        override fun socketOpened(url: String) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                openedAt = observedAt; connectedOnce = true; socketClosed = false
                runtime.observe(EventPayload.SocketConnected(SocketConnectedPayload(url, url, use_canary_server = false,
                    skip_last_voice_sdk_id = false, skip_trailing = false,
                    connect_duration_ms = (observedAt - (socketAt ?: observedAt)).toDouble())), timestamp, noActiveCall = true)
            }
        }
        override fun socketClosed(code: Int?, reason: String?, failed: Boolean, byClient: Boolean) {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                if (socketClosed) return@submit
                socketClosed = true
                runtime.observe(EventPayload.SocketClosed(SocketClosedPayload(code?.toDouble(), reason,
                    if (byClient) SocketClosedPayloadClosedBy.CLIENT else if (failed) SocketClosedPayloadClosedBy.NETWORK else SocketClosedPayloadClosedBy.SERVER,
                    (observedAt - (openedAt ?: observedAt)).toDouble())), timestamp, noActiveCall = true)
            }
        }
        override fun signalingFrame(text: String, outbound: Boolean) {
            val timestamp = clock.wallTimestamp()
            if (text.toByteArray(Charsets.UTF_8).size > MAX_SIGNALING_BYTES) {
                dropped.incrementAndGet()
            } else {
                val raw = try { Json.parseToJsonElement(text) as? JsonObject } catch (_: Exception) { null }
                if (raw != null) submit { runtime.observe(EventPayload.SignalingMessage(SignalingMessagePayload(
                    if (outbound) SignalingMessagePayloadDirection.SENT else SignalingMessagePayloadDirection.RECEIVED, raw)), timestamp) }
            }
        }
        override fun ready() {
            val timestamp = clock.wallTimestamp(); val observedAt = clock.monotonicMs()
            submit {
                if (loginAt == null || readyAttempt == loginAttempts) return@submit
                readyAttempt = loginAttempts
                val elapsed = (observedAt - (loginAt ?: observedAt)).toDouble()
                runtime.observe(EventPayload.LoginSucceeded(LoginSucceededPayload(loginMethod, reconnect, elapsed)), timestamp, noActiveCall = true)
                runtime.observe(EventPayload.ClientReady(ClientReadyPayload(reconnect, null, false, null,
                    calls.values.map { it.lease.callId })), timestamp, noActiveCall = true)
                runtime.observe(EventPayload.SessionTimings(SessionTimingsPayload(reconnect,
                    (observedAt - client.callReportV2CreatedAtMs).toDouble(),
                    (observedAt - (socketAt ?: observedAt)).toDouble(), socket_attempts = socketAttempts, login_attempts = loginAttempts, login_ms = elapsed,
                    socket_connect_ms = openedAt?.let { (it - (socketAt ?: it)).toDouble() })), timestamp, noActiveCall = true)
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
                emitTimings(call, timestamp, complete = false)
            }
        }
        override fun suspendTelemetry() = submit { runtime.suspendTelemetry() }
        override fun isActive(): Boolean = scope.isActive
        override fun dispose() {
            ingressStopped.set(true)
            if (client.callReportV2 === this) client.callReportV2 = null
            ingress.cancel()
            // Immediate revocation: do not drain already queued old-account observations.
            scope.cancel()
        }
        override fun loginType(type: String) = submit {
            login = when (type) { "token" -> LoginType.TOKEN; "anonymous" -> LoginType.ANONYMOUS; else -> LoginType.SIP_CREDENTIALS }
            runtime.updateSignalingContext(null, null, null, login)
        }
        override fun signaling(generation: Long, voiceSdkId: String?, sessionId: String?) = submit {
            runtime.updateSignalingContext(generation.takeIf { it > 0 }, voiceSdkId,
                sessionId?.takeIf { it.isNotBlank() }, login)
        }
        override fun peerClosing(token: V2CaptureToken, report: RTCStatsReport?) {
            val closedAt = clock.monotonicMs()
            val timestamp = clock.wallTimestamp()
            val finalSnapshot = report?.let { extract(it) }
            submit {
            val call = calls[token] ?: return@submit
            // Reconnection disposes a Peer without ending the Call. Reset only the stats baseline.
            if (call.terminal == null) { runtime.peerReplaced(call.lease); return@submit }
            calls.remove(token)
            if (finalSnapshot != null) {
                runtime.sampleStatsAt(call.lease, finalSnapshot,
                    (call.lastSample?.let { closedAt - it } ?: SAMPLE_INTERVAL_MS).coerceAtLeast(1).toDouble(), timestamp)
                call.samples++
            }
            val totals = runtime.callTotals(call.lease) ?: return@submit
            val reason = when (call.terminal) {
                is NativeCallState.ERROR -> CallEndReason.FAILED
                is NativeCallState.DROPPED -> CallEndReason.NETWORK_LOST
                else -> CallEndReason.UNKNOWN
            }
            emitTimings(call, timestamp, complete = true)
            val termination = (call.terminal as? NativeCallState.DONE)?.reason
            runtime.observe(EventPayload.CallEnded(CallEndedPayload(reason, cause = termination?.cause,
                cause_code = termination?.causeCode?.toDouble(), sip_code = termination?.sipCode?.toDouble(), sip_reason = termination?.sipReason, last_state = call.state,
                answered = call.answered, duration_ms = (closedAt - call.began).toDouble(),
                talk_ms = call.answeredAt?.let { (closedAt - it).toDouble() }, metrics_samples = call.samples.toDouble(), totals = totals.toCallTotals(),
                extra = buildJsonObject { put("final_stats_source", if (finalSnapshot != null) "before_peer_disposal" else "last_observed_snapshot_timeout") })), timestamp, lease = call.lease)
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
    }
    val droppedIngress: Long get() = dropped.get()
    suspend fun capturedFrames(): List<String> {
        val barrier = CompletableDeferred<Unit>()
        ingress.send { barrier.complete(Unit) }
        barrier.await()
        return runtime.capturedFrames()
    }
    suspend fun shutdown() {
        if (client.callReportV2 === sink) client.callReportV2 = null
        ingress.close(); worker.join(); runtime.shutdown(); scope.cancel()
    }
    private suspend fun emitTimings(call: Capture, timestamp: String, complete: Boolean) {
        val json = buildJsonObject { put("complete", complete); call.timings.forEach { (field, elapsed) -> put(field, elapsed) } }
        val payload = V2Json.codec.decodeFromJsonElement(CallTimingsPayload.serializer(), json)
        runtime.observe(EventPayload.CallTimings(payload), timestamp, call.lease)
    }
    private fun reportPrefix(stat: com.telnyx.webrtc.lib.RTCStats, selectedPair: String?): String? = when {
        stat.type == "candidate-pair" -> {
            if (stat.id == selectedPair || (selectedPair == null && stat.members["selected"] == true)) "pair" else null
        }
        stat.members["kind"] != "audio" && stat.members["mediaType"] != "audio" -> null
        else -> when (stat.type) {
            "inbound-rtp" -> "in"
            "outbound-rtp" -> "out"
            "remote-inbound-rtp" -> "rin"
            "remote-outbound-rtp" -> "rout"
            else -> null
        }
    }
    private fun extract(report: RTCStatsReport): V2StatsSnapshot {
        val numbers = mutableMapOf<String, Double>()
        var pairId: String? = null
        val reports = report.statsMap.values
        val selectedPair = reports.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
        for (stat in reports) {
            val prefix = reportPrefix(stat, selectedPair) ?: continue
            if (prefix == "pair") pairId = stat.id
            stat.members.forEach { (key, value) ->
                val number = (value as? Number)?.toDouble()
                if (number != null && number.isFinite()) numbers["$prefix.$key"] = number
            }
        }
        return V2StatsSnapshot(numbers, pairId)
    }
}
