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

/** Local capture-only integration. Never opens sockets or exposes account credentials. */
class AndroidV2Capture(private val client: TelnyxClient) {
    private companion object {
        const val INGRESS_CAPACITY = 256
        const val MAX_ACTIVE_CALLS = 128
        const val SAMPLE_INTERVAL_MS = 1000L
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = object : V2Clock {
        override fun monotonicMs() = SystemClock.elapsedRealtime()
        override fun wallTimestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
    private val runtime = V2Runtime(scope, client.callReportV2InstanceId,
        ClientInfo(ClientInfoEnvironment.PRODUCTION, ClientInfoSdk.ANDROID, BuildConfig.SDK_VERSION,
            ClientInfoOs.ANDROID, Build.VERSION.RELEASE, "Android-" + BuildConfig.SDK_VERSION),
        LoginType.SIP_CREDENTIALS, "wss://capture-only.invalid", clock,
        object : V2SocketFactory {
            override fun open(endpoint: String, epoch: Long, listener: V2SocketListener): V2Socket =
                error("Capture-only adapter must never open a socket")
        }, captureOnly = true)
    private val ingress = Channel<suspend () -> Unit>(INGRESS_CAPACITY)
    private val dropped = AtomicLong()
    private data class Capture(val lease: V2CallLease, val began: Long, var state: CallState = CallState.NEW,
        var answered: Boolean = false, var samples: Int = 0, var lastSample: Long? = null,
        var terminal: NativeCallState? = null)
    private var login = LoginType.SIP_CREDENTIALS
    private val calls = mutableMapOf<V2CaptureToken, Capture>()
    private val worker = scope.launch {
        for (command in ingress) try { command() } catch (_: Exception) { dropped.incrementAndGet() }
    }
    private fun submit(action: suspend () -> Unit) {
        if (ingress.trySend(action).isFailure) dropped.incrementAndGet()
    }
    private val sink = object : CallReportV2Ingress {
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
                if (mapped == CallState.ACTIVE) call.answered = true
            }
        }
        override fun stats(token: V2CaptureToken, report: RTCStatsReport) {
            val sampled = clock.monotonicMs()
            val snapshot = extract(report)
            submit {
                val call = calls[token] ?: return@submit
                val previous = call.lastSample
                if (previous != null && sampled - previous < SAMPLE_INTERVAL_MS) return@submit
                runtime.sampleStats(call.lease, snapshot, (previous?.let { sampled - it } ?: SAMPLE_INTERVAL_MS).toDouble())
                call.samples++; call.lastSample = sampled
            }
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
                runtime.sampleStats(call.lease, finalSnapshot,
                    (call.lastSample?.let { closedAt - it } ?: SAMPLE_INTERVAL_MS).coerceAtLeast(1).toDouble())
                call.samples++
            }
            val totals = runtime.callTotals(call.lease) ?: return@submit
            val reason = when (call.terminal) {
                is NativeCallState.ERROR -> CallEndReason.FAILED
                is NativeCallState.DROPPED -> CallEndReason.NETWORK_LOST
                else -> CallEndReason.UNKNOWN
            }
            runtime.observe(EventPayload.CallEnded(CallEndedPayload(reason, last_state = call.state,
                answered = call.answered, duration_ms = (closedAt - call.began).toDouble(),
                metrics_samples = call.samples.toDouble(), totals = totals.toCallTotals(),
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
