/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects debug data during a call and logs it at the end of the call.
 * This class is responsible for gathering essential connection, signaling,
 * and media metrics to help with debugging and troubleshooting.
 */
class DebugDataCollector(private val context: Context) {

    companion object {
        private const val TAG = "DebugDataCollector"
        private const val LOG_SEPARATOR = "═══════════════════════════════════════════════════════════════"
        private const val LOG_SECTION_SEPARATOR = "───────────────────────────────────────────────────────────────"
        private const val MILLIS_PER_SECOND = 1000
    }

    private val callDebugData = ConcurrentHashMap<UUID, CallDebugData>()

    /**
     * Called when a new call is started. Initializes debug data collection for the call.
     *
     * @param callId The unique identifier for the call
     * @param telnyxSessionId The Telnyx session ID (optional, may be set later)
     * @param telnyxLegId The Telnyx leg ID (optional, may be set later)
     */
    fun onCallStarted(
        callId: UUID,
        telnyxSessionId: UUID? = null,
        telnyxLegId: UUID? = null
    ) {
        val debugData = CallDebugData(
            callId = callId,
            telnyxSessionId = telnyxSessionId,
            telnyxLegId = telnyxLegId,
            startTimestamp = System.currentTimeMillis(),
            sdkVersion = BuildConfig.SDK_VERSION,
            networkType = getNetworkType(),
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
        callDebugData[callId] = debugData
        Timber.tag(TAG).d("Call started - collecting debug data for call: $callId")
    }

    /**
     * Updates the Telnyx identifiers for a call (may be received after call starts).
     *
     * @param callId The unique identifier for the call
     * @param telnyxSessionId The Telnyx session ID
     * @param telnyxLegId The Telnyx leg ID
     */
    fun updateTelnyxIdentifiers(
        callId: UUID,
        telnyxSessionId: UUID?,
        telnyxLegId: UUID?
    ) {
        callDebugData[callId]?.let { data ->
            data.telnyxSessionId = telnyxSessionId
            data.telnyxLegId = telnyxLegId
        }
    }

    /**
     * Records an ICE gathering state change.
     *
     * @param callId The unique identifier for the call
     * @param state The new ICE gathering state (new, gathering, complete)
     */
    fun onIceGatheringStateChange(callId: UUID, state: String) {
        callDebugData[callId]?.let { data ->
            data.iceGatheringStates.add(StateChange(state, System.currentTimeMillis()))
            data.lastIceGatheringState = state
        }
    }

    /**
     * Records an ICE connection state change.
     *
     * @param callId The unique identifier for the call
     * @param state The new ICE connection state (new, checking, connected, completed, disconnected, failed, closed)
     */
    fun onIceConnectionStateChange(callId: UUID, state: String) {
        callDebugData[callId]?.let { data ->
            data.iceConnectionStates.add(StateChange(state, System.currentTimeMillis()))
            data.lastIceConnectionState = state
        }
    }

    /**
     * Records a peer connection state change.
     *
     * @param callId The unique identifier for the call
     * @param state The new connection state (new, connecting, connected, disconnected, failed, closed)
     */
    fun onConnectionStateChange(callId: UUID, state: String) {
        callDebugData[callId]?.let { data ->
            data.connectionStates.add(StateChange(state, System.currentTimeMillis()))
            data.lastConnectionState = state
        }
    }

    /**
     * Records a signaling state change.
     *
     * @param callId The unique identifier for the call
     * @param state The new signaling state
     */
    fun onSignalingStateChange(callId: UUID, state: String) {
        callDebugData[callId]?.let { data ->
            data.signalingStates.add(StateChange(state, System.currentTimeMillis()))
            data.lastSignalingState = state
        }
    }

    /**
     * Records the codec being used for the call.
     *
     * @param callId The unique identifier for the call
     * @param codecName The name/mimeType of the codec
     */
    fun onCodecSelected(callId: UUID, codecName: String) {
        callDebugData[callId]?.let { data ->
            data.selectedCodec = codecName
        }
    }

    /**
     * Records an ICE candidate being added.
     *
     * @param callId The unique identifier for the call
     * @param candidateType The type of ICE candidate (host, srflx, relay)
     * @param protocol The protocol (udp, tcp)
     */
    fun onIceCandidateAdded(callId: UUID, candidateType: String, protocol: String) {
        callDebugData[callId]?.let { data ->
            data.iceCandidates.add(IceCandidateInfo(candidateType, protocol, System.currentTimeMillis()))
        }
    }

    /**
     * Records the selected ICE candidate pair.
     *
     * @param callId The unique identifier for the call
     * @param localCandidateType The type of local candidate
     * @param remoteCandidateType The type of remote candidate
     * @param protocol The protocol being used
     */
    fun onIceCandidatePairSelected(
        callId: UUID,
        localCandidateType: String,
        remoteCandidateType: String,
        protocol: String
    ) {
        callDebugData[callId]?.let { data ->
            data.selectedCandidatePair = CandidatePairInfo(localCandidateType, remoteCandidateType, protocol)
        }
    }

    /**
     * Updates media statistics for the call.
     *
     * @param callId The unique identifier for the call
     * @param stats The media statistics to record
     */
    fun updateMediaStats(callId: UUID, stats: MediaStats) {
        callDebugData[callId]?.let { data ->
            data.lastMediaStats = stats
            if (data.firstMediaStats == null) {
                data.firstMediaStats = stats
            }
        }
    }

    /**
     * Records an audio device change event.
     *
     * @param callId The unique identifier for the call
     * @param event Description of the audio device change
     */
    fun onAudioDeviceChange(callId: UUID, event: String) {
        callDebugData[callId]?.let { data ->
            data.audioDeviceEvents.add(AudioDeviceEvent(event, System.currentTimeMillis()))
        }
    }

    /**
     * Records a microphone access error.
     *
     * @param callId The unique identifier for the call
     * @param error Description of the error
     */
    fun onMicrophoneAccessError(callId: UUID, error: String) {
        callDebugData[callId]?.let { data ->
            data.microphoneErrors.add(error)
        }
    }

    /**
     * Records a track state change (muted, unmuted, ended).
     *
     * @param callId The unique identifier for the call
     * @param trackType The type of track (audio/video)
     * @param state The new state
     */
    fun onTrackStateChange(callId: UUID, trackType: String, state: String) {
        callDebugData[callId]?.let { data ->
            data.trackStateChanges.add(TrackStateChange(trackType, state, System.currentTimeMillis()))
        }
    }

    /**
     * Called when a call ends. Logs all collected debug data using Timber.
     *
     * @param callId The unique identifier for the call
     * @param endReason The reason the call ended (optional)
     */
    fun onCallEnded(callId: UUID, endReason: String? = null) {
        val data = callDebugData.remove(callId) ?: run {
            Timber.tag(TAG).w("No debug data found for call: $callId")
            return
        }

        data.endTimestamp = System.currentTimeMillis()
        data.endReason = endReason

        logCallDebugData(data)
    }

    private fun logCallDebugData(data: CallDebugData) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val duration = data.endTimestamp?.let { (it - data.startTimestamp) / MILLIS_PER_SECOND.toDouble() } ?: 0.0

        val logBuilder = StringBuilder()
        logBuilder.appendLine()
        logBuilder.appendLine(LOG_SEPARATOR)
        logBuilder.appendLine("📞 CALL DEBUG DATA REPORT")
        logBuilder.appendLine(LOG_SEPARATOR)

        // Basic Identifiers
        logBuilder.appendLine()
        logBuilder.appendLine("📋 BASIC IDENTIFIERS")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Call ID:           ${data.callId}")
        logBuilder.appendLine("  Telnyx Session ID: ${data.telnyxSessionId ?: "N/A"}")
        logBuilder.appendLine("  Telnyx Leg ID:     ${data.telnyxLegId ?: "N/A"}")
        logBuilder.appendLine("  SDK Version:       ${data.sdkVersion}")
        logBuilder.appendLine("  Start Time:        ${dateFormat.format(Date(data.startTimestamp))}")
        data.endTimestamp?.let {
            logBuilder.appendLine("  End Time:          ${dateFormat.format(Date(it))}")
        }
        logBuilder.appendLine("  Duration:          ${String.format(Locale.US, "%.2f", duration)} seconds")
        data.endReason?.let {
            logBuilder.appendLine("  End Reason:        $it")
        }

        // Device & Network Info
        logBuilder.appendLine()
        logBuilder.appendLine("📱 DEVICE & NETWORK")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Device:            ${data.deviceModel}")
        logBuilder.appendLine("  OS:                ${data.osVersion}")
        logBuilder.appendLine("  Network Type:      ${data.networkType}")

        // Connection States
        logBuilder.appendLine()
        logBuilder.appendLine("🔗 CONNECTION STATES")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Last ICE Gathering State:   ${data.lastIceGatheringState ?: "N/A"}")
        logBuilder.appendLine("  Last ICE Connection State:  ${data.lastIceConnectionState ?: "N/A"}")
        logBuilder.appendLine("  Last Connection State:      ${data.lastConnectionState ?: "N/A"}")
        logBuilder.appendLine("  Last Signaling State:       ${data.lastSignalingState ?: "N/A"}")

        // State Change History
        if (data.iceGatheringStates.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("  ICE Gathering State History:")
            data.iceGatheringStates.forEach { change ->
                val relativeTime = (change.timestamp - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
                logBuilder.appendLine("    +${String.format(Locale.US, "%.3f", relativeTime)}s: ${change.state}")
            }
        }

        if (data.iceConnectionStates.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("  ICE Connection State History:")
            data.iceConnectionStates.forEach { change ->
                val relativeTime = (change.timestamp - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
                logBuilder.appendLine("    +${String.format(Locale.US, "%.3f", relativeTime)}s: ${change.state}")
            }
        }

        if (data.connectionStates.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("  Connection State History:")
            data.connectionStates.forEach { change ->
                val relativeTime = (change.timestamp - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
                logBuilder.appendLine("    +${String.format(Locale.US, "%.3f", relativeTime)}s: ${change.state}")
            }
        }

        // ICE Candidates
        logBuilder.appendLine()
        logBuilder.appendLine("🧊 ICE CANDIDATES")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Total Candidates:  ${data.iceCandidates.size}")
        if (data.iceCandidates.isNotEmpty()) {
            val candidatesByType = data.iceCandidates.groupBy { it.type }
            candidatesByType.forEach { (type, candidates) ->
                logBuilder.appendLine("    $type: ${candidates.size}")
            }
        }
        data.selectedCandidatePair?.let { pair ->
            logBuilder.appendLine("  Selected Pair:")
            logBuilder.appendLine("    Local:  ${pair.localType}")
            logBuilder.appendLine("    Remote: ${pair.remoteType}")
            logBuilder.appendLine("    Protocol: ${pair.protocol}")
        }

        // Codec
        logBuilder.appendLine()
        logBuilder.appendLine("🎵 CODEC")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Selected Codec:    ${data.selectedCodec ?: "N/A"}")

        // Media Statistics
        logBuilder.appendLine()
        logBuilder.appendLine("📊 MEDIA STATISTICS")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        data.lastMediaStats?.let { stats ->
            logBuilder.appendLine("  Outbound RTP:")
            logBuilder.appendLine("    Packets Sent:    ${stats.outboundPacketsSent}")
            logBuilder.appendLine("    Bytes Sent:      ${stats.outboundBytesSent}")
            logBuilder.appendLine("    Audio Energy:    ${String.format(Locale.US, "%.6f", stats.outboundAudioEnergy)}")
            logBuilder.appendLine()
            logBuilder.appendLine("  Inbound RTP:")
            logBuilder.appendLine("    Packets Received: ${stats.inboundPacketsReceived}")
            logBuilder.appendLine("    Bytes Received:   ${stats.inboundBytesReceived}")
            logBuilder.appendLine("    Packets Lost:     ${stats.inboundPacketsLost}")
            logBuilder.appendLine("    Jitter:           ${String.format(Locale.US, "%.3f", stats.inboundJitter)} ms")
            logBuilder.appendLine("    Audio Level:      ${String.format(Locale.US, "%.6f", stats.inboundAudioLevel)}")
            logBuilder.appendLine()
            logBuilder.appendLine("  Connection Quality:")
            logBuilder.appendLine("    Round Trip Time:  ${String.format(Locale.US, "%.3f", stats.roundTripTime)} ms")
        } ?: run {
            logBuilder.appendLine("  No media statistics available")
        }

        // Audio Device Events
        if (data.audioDeviceEvents.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("🔊 AUDIO DEVICE EVENTS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.audioDeviceEvents.forEach { event ->
                val relativeTime = (event.timestamp - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
                logBuilder.appendLine("  +${String.format(Locale.US, "%.3f", relativeTime)}s: ${event.event}")
            }
        }

        // Microphone Errors
        if (data.microphoneErrors.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("⚠️ MICROPHONE ERRORS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.microphoneErrors.forEach { error ->
                logBuilder.appendLine("  - $error")
            }
        }

        // Track State Changes
        if (data.trackStateChanges.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("🎤 TRACK STATE CHANGES")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.trackStateChanges.forEach { change ->
                val relativeTime = (change.timestamp - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
                logBuilder.appendLine("  +${String.format(Locale.US, "%.3f", relativeTime)}s: ${change.trackType} -> ${change.state}")
            }
        }

        logBuilder.appendLine()
        logBuilder.appendLine(LOG_SEPARATOR)

        Timber.tag(TAG).i(logBuilder.toString())
    }

    private fun getNetworkType(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(network)

            when {
                capabilities == null -> "Unknown"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Other"
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error getting network type")
            "Unknown"
        }
    }

    /**
     * Clears all debug data. Should be called when the TelnyxClient is destroyed.
     */
    fun clear() {
        callDebugData.clear()
    }
}

/**
 * Internal data class to hold all debug data for a single call.
 */
internal data class CallDebugData(
    val callId: UUID,
    var telnyxSessionId: UUID? = null,
    var telnyxLegId: UUID? = null,
    val startTimestamp: Long,
    var endTimestamp: Long? = null,
    var endReason: String? = null,
    val sdkVersion: String,
    val networkType: String,
    val osVersion: String,
    val deviceModel: String,

    // State tracking
    val iceGatheringStates: MutableList<StateChange> = mutableListOf(),
    val iceConnectionStates: MutableList<StateChange> = mutableListOf(),
    val connectionStates: MutableList<StateChange> = mutableListOf(),
    val signalingStates: MutableList<StateChange> = mutableListOf(),
    var lastIceGatheringState: String? = null,
    var lastIceConnectionState: String? = null,
    var lastConnectionState: String? = null,
    var lastSignalingState: String? = null,

    // ICE candidates
    val iceCandidates: MutableList<IceCandidateInfo> = mutableListOf(),
    var selectedCandidatePair: CandidatePairInfo? = null,

    // Codec
    var selectedCodec: String? = null,

    // Media stats
    var firstMediaStats: MediaStats? = null,
    var lastMediaStats: MediaStats? = null,

    // Audio device events
    val audioDeviceEvents: MutableList<AudioDeviceEvent> = mutableListOf(),
    val microphoneErrors: MutableList<String> = mutableListOf(),
    val trackStateChanges: MutableList<TrackStateChange> = mutableListOf()
)

/**
 * Represents a state change with timestamp.
 */
internal data class StateChange(
    val state: String,
    val timestamp: Long
)

/**
 * Represents ICE candidate information.
 */
internal data class IceCandidateInfo(
    val type: String,
    val protocol: String,
    val timestamp: Long
)

/**
 * Represents the selected ICE candidate pair.
 */
internal data class CandidatePairInfo(
    val localType: String,
    val remoteType: String,
    val protocol: String
)

/**
 * Represents an audio device event.
 */
internal data class AudioDeviceEvent(
    val event: String,
    val timestamp: Long
)

/**
 * Represents a track state change.
 */
internal data class TrackStateChange(
    val trackType: String,
    val state: String,
    val timestamp: Long
)

/**
 * Represents media statistics for a call.
 */
data class MediaStats(
    val outboundPacketsSent: Long = 0,
    val outboundBytesSent: Long = 0,
    val outboundAudioEnergy: Double = 0.0,
    val inboundPacketsReceived: Long = 0,
    val inboundBytesReceived: Long = 0,
    val inboundPacketsLost: Long = 0,
    val inboundJitter: Double = 0.0,
    val inboundAudioLevel: Double = 0.0,
    val roundTripTime: Double = 0.0
)
