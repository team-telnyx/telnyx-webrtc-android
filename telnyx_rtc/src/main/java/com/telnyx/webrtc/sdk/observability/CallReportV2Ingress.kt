package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.model.CallState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * Sanitized app-supplied call options for `raw_call_options`: names only for custom
 * headers; no passwords/tokens/SDP. An inbound call at call_started time receives {}
 * — the app supplies options at answer time, which is never back-filled into the
 * earlier call_started record.
 */
internal fun v2RawCallOptions(
    callerName: String?, callerNumber: String?, destinationNumber: String?, clientState: String?,
    customHeaderNames: List<String>?, preferredCodecs: List<String>?
): JsonObject = buildJsonObject {
    callerName?.takeIf { it.isNotBlank() }?.let { put("caller_name", it) }
    callerNumber?.takeIf { it.isNotBlank() }?.let { put("caller_number", it) }
    destinationNumber?.takeIf { it.isNotBlank() }?.let { put("destination_number", it) }
    clientState?.takeIf { it.isNotEmpty() }?.let { put("client_state", it) }
    customHeaderNames?.takeIf { it.isNotEmpty() }?.let { names ->
        put("custom_header_names", buildJsonArray { names.forEach { name -> add(JsonPrimitive(name)) } })
    }
    preferredCodecs?.takeIf { it.isNotEmpty() }?.let { codecs ->
        put("preferred_codecs", buildJsonArray { codecs.forEach { codec -> add(JsonPrimitive(codec)) } })
    }
}

/** Per-call identity, retained by the Call and its original Peer across delayed callbacks. */
internal data class V2CaptureOptions(val forceRelay: Boolean, val prefetch: Boolean,
    val customIce: Boolean,
    // t029: provenance versus effective ICE facts are reported separately and never mixed.
    // configuredIceServerCount: how many TURN/STUN server URLs this client was configured
    // with before derivation (the SDK defaults when the app set none; the client provides
    // these values, so their deeper origin is not asserted). effectiveIceServerCount: the
    // actual number of servers the native Peer builds for this call, SDK defaults included,
    // from the shared [Peer.iceServerPlan] — never a hardcoded or fabricated count.
    val configuredIceServerCount: Int,
    val effectiveIceServerCount: Int,
    // t027: call options as actually supplied/requested by the app or observed signaling.
    // Header VALUES are never carried; only sanitized names. Secrets/SDP never leave the
    // signaling path (see FIELD_DELTA.csv for per-field evidence).
    val callerName: String? = null,
    val callerNumber: String? = null,
    val destinationNumber: String? = null,
    // Sanitized app-supplied options: built by the call site (outbound args); {} for inbound.
    val rawCallOptions: JsonObject = JsonObject(emptyMap()),
    val customHeaderNames: List<String>? = null,
    val preferredCodecs: List<String>? = null,
    val useStereo: Boolean? = null)

internal sealed class V2NativeFact {
    data class MicrophoneMuteSet(val muted: Boolean) : V2NativeFact()
    data class SpeakerphoneRequested(val enabled: Boolean) : V2NativeFact()
    data class AudioOutputRequested(val device: com.telnyx.webrtc.sdk.model.AudioDevice) : V2NativeFact()
    object PushMetadataProcessed : V2NativeFact()
    data class MicrophonePermission(val granted: Boolean) : V2NativeFact()
    data class MicrophoneSystemMute(val muted: Boolean) : V2NativeFact()
}

internal data class V2PeerConfiguration(val relayOnly: Boolean, val candidatePoolSize: Int, val iceServerCount: Int)

/** t027: optional measured facts that tripped a warning; null fields stay null (honest unmeasured). */
internal data class V2WarningMeasurement(val metric: String?, val value: Double?, val threshold: Double?)

internal class V2CaptureToken

/** Native ingress only; the optional KMP adapter owns all reporting state and serialization. */
internal interface CallReportV2Ingress {
    fun started(token: V2CaptureToken, callId: String, inbound: Boolean, reattach: Boolean, trickle: Boolean, options: V2CaptureOptions)
    fun state(token: V2CaptureToken, state: CallState)
    /** True while the reporting owner holds live call leases; reconfiguration must not dispose it. */
    fun hasActiveCall(): Boolean = false
    fun sendsToServer(): Boolean = false
    fun sendsToServerForLogin(): Boolean = sendsToServer()
    fun collectsStats(): Boolean = true
    fun stats(token: V2CaptureToken, report: RTCStatsReport)
    fun authenticate(type: String, identity: String?, secret: String?) {}
    fun connectChainStarted() {}
    fun socketStarting(url: String, region: String? = null, resumeVoiceSdkId: String? = null) {}
    fun socketOpened(url: String, finalUrl: String = url, region: String? = null, resumeVoiceSdkId: String? = null) {}
    fun socketClosed(code: Int?, reason: String?, failed: Boolean, byClient: Boolean = false, willReconnect: Boolean? = null) {}
    fun signalingWriteStarted(text: String) {}
    fun signalingWriteRejected(text: String) {}
    fun signalingFrame(text: String, outbound: Boolean) {}
    fun ready() {}
    fun pushEvent(event: CallReportV2PushEvent) {}
    suspend fun awaitDrain(timeoutMs: Long): Boolean = true
    /** Diagnostic access to locally captured frames; empty for mock sinks. */
    suspend fun capturedFrames(): List<String> = emptyList()

    /**
     * Ordered, non-blocking environment/endpoint retarget request for the SAME reporting owner;
     * queued through the same ingress as authenticate. [guard] is evaluated on the reporting
     * worker immediately before the shared call, so superseded requests are refused without
     * touching the runtime. Queue acceptance does not imply application: exactly one of
     * [onApplied]/[onRejected] runs after the shared outcome, and a request dropped by a disposed
     * or replaced owner invokes neither callback.
     */
    fun retarget(requested: CallReportV2Config, guard: () -> Boolean, onApplied: () -> Unit, onRejected: (Throwable) -> Unit) {
        onRejected(UnsupportedOperationException("no reporting owner is attached for this client"))
    }
    fun creationFinished(durationMs: Long) {}
    fun creationFailed(errorName: String) {}
    fun devices(devices: List<V2AudioDevice>) {}
    fun outputDevice(device: V2AudioDevice) {}
    fun socketFailed(message: String, willRetry: Boolean) {}
    fun gatewayCheckStarted(id: String) {}
    fun gatewayCheckResult(id: String, state: String) {}
    fun gatewayCheckFailed(id: String, message: String, willRetry: Boolean) {}
    fun providerIds(token: V2CaptureToken, legId: String?, sessionId: String?) {}
    fun termination(token: V2CaptureToken, local: Boolean) {}
    /** signaled is JS-style eligibility at observation time, not socket/server acceptance. */
    fun candidate(token: V2CaptureToken, sdp: String, remote: Boolean, ufrag: String? = null, signaled: Boolean? = null,
        serverUrl: String? = null, adapterType: PeerConnection.AdapterType? = null) {}
    fun description(token: V2CaptureToken, sdp: String, remote: Boolean) {}
    fun inputTrack(token: V2CaptureToken, enabled: Boolean, ended: Boolean = false, muted: Boolean? = null) {}
    fun peerConfiguration(token: V2CaptureToken, configuration: V2PeerConfiguration) {}
    fun peerMedia(token: V2CaptureToken, media: V2PeerMedia) {}
    fun peerState(token: V2CaptureToken, kind: String, state: String) {}
    fun network(type: String, online: Boolean) {}
    fun nativeFact(fact: V2NativeFact, token: V2CaptureToken? = null) {}
    fun error(error: com.telnyx.webrtc.sdk.model.TelnyxError, token: V2CaptureToken?) {}
    fun warning(warning: com.telnyx.webrtc.sdk.model.TelnyxWarning, token: V2CaptureToken?,
        measurement: V2WarningMeasurement? = null) {}
    fun log(level: String, message: String) {}
    fun rpcFailed(id: String?, code: Int?, message: String, willRetry: Boolean) {}
    fun appState(background: Boolean) {}
    fun iceConnectionState(token: V2CaptureToken, state: String) {}
    fun gateway(state: String) {}
    fun timing(token: V2CaptureToken, field: String) {}
    fun suspendTelemetry() {}
    fun dispose() {}
    fun isActive(): Boolean = true
    fun loginType(type: String)
    fun signaling(generation: Long, voiceSdkId: String?, sessionId: String?)
    fun peerClosing(token: V2CaptureToken, report: RTCStatsReport?)
}
