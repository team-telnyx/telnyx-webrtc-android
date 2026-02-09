/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects debug data during a call and logs it at the end of the call.
 * This class is responsible for gathering essential connection, signaling,
 * and media metrics to help with debugging and troubleshooting.
 * Also creates a JSON file with call statistics when the call ends.
 */
class DebugDataCollector(private val context: Context) {

    companion object {
        private const val TAG = "DebugDataCollector"
        private const val LOG_SEPARATOR = "═══════════════════════════════════════════════════════════════"
        private const val LOG_SECTION_SEPARATOR = "───────────────────────────────────────────────────────────────"
        private const val MILLIS_PER_SECOND = 1000
        private const val PERCENT_FACTOR = 100.0
        private const val CALL_STATS_DIR = "call_stats"
        private const val JSON_FILE_PREFIX = "call_stats_"
        private const val JSON_FILE_EXTENSION = ".json"
    }

    private val callDebugData = ConcurrentHashMap<UUID, CallDebugData>()
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var lastGeneratedJsonFilePath: String? = null

    // Upload configuration
    private var callReportId: String? = null
    private var voiceSDKID: String? = null
    private var uploadEnabled: Boolean = false
    private var callStatsUploader: CallStatsUploader? = null

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
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            recordAudioPermissionGranted = checkPermission(Manifest.permission.RECORD_AUDIO),
            postNotificationsPermissionGranted = checkPermission(Manifest.permission.POST_NOTIFICATIONS)
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
     * Records an ICE connection state change and adds it to the connection timeline.
     *
     * @param callId The unique identifier for the call
     * @param state The new ICE connection state (new, checking, connected, completed, disconnected, failed, closed)
     */
    fun onIceConnectionStateChange(callId: UUID, state: String) {
        callDebugData[callId]?.let { data ->
            val timestamp = System.currentTimeMillis()
            data.iceConnectionStates.add(StateChange(state, timestamp))
            data.lastIceConnectionState = state

            // Track ICE connected timestamp for DTLS timeout detection
            if (state.uppercase() == "CONNECTED" && data.iceConnectedTimestamp == null) {
                data.iceConnectedTimestamp = timestamp
            }

            // Add to connection timeline
            val event = when (state.uppercase()) {
                "CHECKING" -> ConnectionStateEvent.IceChecking(timestamp)
                "CONNECTED" -> ConnectionStateEvent.IceConnected(timestamp)
                "COMPLETED" -> ConnectionStateEvent.IceCompleted(timestamp)
                "DISCONNECTED" -> ConnectionStateEvent.IceDisconnected(timestamp)
                "FAILED" -> ConnectionStateEvent.IceFailed(timestamp)
                "CLOSED" -> ConnectionStateEvent.IceClosed(timestamp)
                else -> null
            }
            event?.let { data.connectionTimeline.add(it) }
        }
    }

    /**
     * Records a DTLS state change. Should be called when polling getStats() for transport stats.
     * Automatically detects DTLS failures and adds them to the connection timeline.
     *
     * @param callId The unique identifier for the call
     * @param dtlsState The DTLS state from transport stats (new, connecting, connected, closed, failed)
     */
    fun onDtlsStateChange(callId: UUID, dtlsState: String) {
        callDebugData[callId]?.let { data ->
            // Only add if this is a new state transition
            if (data.lastDtlsState != dtlsState) {
                val timestamp = System.currentTimeMillis()
                data.lastDtlsState = dtlsState

                // Track DTLS connected timestamp
                if (dtlsState.uppercase() == "CONNECTED" && data.dtlsConnectedTimestamp == null) {
                    data.dtlsConnectedTimestamp = timestamp
                }

                val event = when (dtlsState.uppercase()) {
                    "CONNECTING" -> ConnectionStateEvent.DtlsConnecting(timestamp)
                    "CONNECTED" -> {
                        // Calculate time from ICE connected to DTLS connected
                        data.iceConnectedTimestamp?.let { iceTime ->
                            val dtlsDelay = timestamp - iceTime
                            Timber.tag(TAG).d("DTLS connected ${dtlsDelay}ms after ICE connected")
                        }
                        ConnectionStateEvent.DtlsConnected(timestamp)
                    }
                    "FAILED" -> {
                        // DTLS explicitly failed - record this as a connection failure
                        val reason = if (data.lastIceConnectionState?.uppercase() == "CONNECTED") {
                            "ICE connected but DTLS handshake failed"
                        } else {
                            "DTLS handshake failed"
                        }
                        // First add the DTLS failed event
                        data.connectionTimeline.add(ConnectionStateEvent.DtlsFailed(timestamp, reason))
                        // Then add the connection failed event
                        data.connectionTimeline.add(ConnectionStateEvent.ConnectionFailed(timestamp, reason))
                        null // Don't add event again below
                    }
                    else -> null
                }
                event?.let { data.connectionTimeline.add(it) }
            }
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
     * Records the selected ICE candidate pair with full details.
     *
     * @param callId The unique identifier for the call
     * @param localCandidate The local ICE candidate details
     * @param remoteCandidate The remote ICE candidate details
     */
    fun onIceCandidatePairSelected(
        callId: UUID,
        localCandidate: CandidateDetails,
        remoteCandidate: CandidateDetails
    ) {
        callDebugData[callId]?.let { data ->
            data.selectedCandidatePair = CandidatePairInfo(localCandidate, remoteCandidate)
        }
    }

    /**
     * Updates media statistics for the call by accumulating the new values.
     * Each stats update contains incremental values since the last update,
     * so we add them to the existing accumulated totals. For instantaneous
     * metrics like jitter, audio level, and RTT, we calculate running averages.
     *
     * @param callId The unique identifier for the call
     * @param stats The media statistics to add to accumulated totals
     */
    fun updateMediaStats(callId: UUID, stats: MediaStats) {
        callDebugData[callId]?.let { data ->
            val currentStats = data.mediaStats

            // Increment sample count
            data.statsUpdateCount++

            // Accumulate sums for averaging
            data.jitterSum += stats.inboundJitter
            data.audioLevelSum += stats.inboundAudioLevel
            data.rttSum += stats.roundTripTime

            // Calculate averages
            val avgJitter = data.jitterSum / data.statsUpdateCount
            val avgAudioLevel = data.audioLevelSum / data.statsUpdateCount
            val avgRTT = data.rttSum / data.statsUpdateCount

            data.mediaStats = MediaStats(
                outboundPacketsSent = currentStats.outboundPacketsSent + stats.outboundPacketsSent,
                outboundBytesSent = currentStats.outboundBytesSent + stats.outboundBytesSent,
                outboundAudioEnergy = currentStats.outboundAudioEnergy + stats.outboundAudioEnergy,
                inboundPacketsReceived = currentStats.inboundPacketsReceived + stats.inboundPacketsReceived,
                inboundBytesReceived = currentStats.inboundBytesReceived + stats.inboundBytesReceived,
                inboundPacketsLost = currentStats.inboundPacketsLost + stats.inboundPacketsLost,
                inboundJitter = avgJitter, // Average jitter value
                inboundAudioLevel = avgAudioLevel, // Average audio level
                roundTripTime = avgRTT // Average RTT
            )
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
            data.microphoneErrors.add(MicrophoneError(error, System.currentTimeMillis()))
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
     * Records a getUserMedia equivalent event (audio track creation).
     *
     * @param callId The unique identifier for the call
     * @param trackId The ID of the audio track (e.g., "audio_local_track")
     * @param state The state of the track (e.g., "LIVE", "ENDED")
     * @param enabled Whether the track is enabled
     */
    fun onGetUserMediaAttempt(callId: UUID, trackId: String, state: String, enabled: Boolean) {
        callDebugData[callId]?.let { data ->
            data.getUserMediaEvents.add(
                GetUserMediaEvent(trackId, state, enabled, System.currentTimeMillis())
            )
        }
    }

    /**
     * Records a speaker/audio output device change.
     *
     * @param callId The unique identifier for the call
     * @param device The audio output device (e.g., SPEAKER, EARPIECE, BLUETOOTH)
     * @param isActive Whether this device is now active
     */
    fun onSpeakerOutputChange(callId: UUID, device: String, isActive: Boolean) {
        callDebugData[callId]?.let { data ->
            data.speakerOutputEvents.add(
                SpeakerOutputEvent(device, isActive, System.currentTimeMillis())
            )
        }
    }

    /**
     * Called when a call ends. Logs all collected debug data using Timber
     * and creates a JSON file with the call statistics.
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
        saveCallStatsToJsonFile(data)

        // Upload stats if enabled and all required values are present
        if (uploadEnabled && callReportId != null && voiceSDKID != null) {
            val jsonContent = gson.toJson(convertToJson(data))
            callStatsUploader?.uploadCallStats(
                callReportId = callReportId!!,
                callId = callId,
                voiceSdkId = voiceSDKID!!,
                jsonContent = jsonContent
            )
        }
    }

    /**
     * Returns the path to the last generated JSON file containing call statistics.
     * This can be used to retrieve the file for sending to a server or for debugging.
     *
     * @return The absolute path to the last generated JSON file, or null if no file has been generated
     */
    fun getLastGeneratedJsonFilePath(): String? = lastGeneratedJsonFilePath

    /**
     * Converts the call debug data to a structured JSON object.
     * The JSON structure has a top-level "data" wrapper containing
     * "summary" and "stats" sections.
     *
     * @param data The call debug data to convert
     * @return A JsonObject containing the structured call statistics
     */
    @Suppress("LongMethod")
    internal fun convertToJson(data: CallDebugData): JsonObject {
        val json = JsonObject()
        val dataWrapper = JsonObject()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        val duration = data.endTimestamp?.let {
            (it - data.startTimestamp) / MILLIS_PER_SECOND.toDouble()
        } ?: 0.0

        // Summary section with nested permissions
        val summary = JsonObject().apply {
            addProperty("callId", data.callId.toString())
            addProperty("telnyxSessionId", data.telnyxSessionId?.toString())
            addProperty("telnyxLegId", data.telnyxLegId?.toString())
            addProperty("sdkVersion", data.sdkVersion)
            addProperty("userAgent", "TelnyxAndroidSDK/${data.sdkVersion}")
            addProperty("startTimestamp", dateFormat.format(Date(data.startTimestamp)))
            data.endTimestamp?.let { addProperty("endTimestamp", dateFormat.format(Date(it))) }
            addProperty("durationSeconds", duration)
            addProperty("endReason", data.endReason)
            addProperty("networkType", data.networkType)
            addProperty("osVersion", data.osVersion)
            addProperty("deviceModel", data.deviceModel)
            addProperty("selectedCodec", data.selectedCodec)

            // Nested permissions
            val permissions = JsonObject().apply {
                addProperty("microphone", data.recordAudioPermissionGranted)
                addProperty("notifications", data.postNotificationsPermissionGranted)
            }
            add("permissions", permissions)
        }
        dataWrapper.add("summary", summary)

        // Stats section
        val stats = JsonObject()

        // Connection state
        val connectionState = JsonObject().apply {
            addProperty("lastIceGatheringState", data.lastIceGatheringState)
            addProperty("lastIceConnectionState", data.lastIceConnectionState)
            addProperty("lastDtlsState", data.lastDtlsState)
            addProperty("lastSignalingState", data.lastSignalingState)
        }
        stats.add("connectionState", connectionState)

        // Connection timeline
        stats.add("connectionTimeline", createConnectionTimelineArray(data.connectionTimeline, dateFormat))

        // State transitions
        val stateTransitions = JsonObject().apply {
            add("iceGathering", createStateChangeArray(data.iceGatheringStates, dateFormat))
            add("iceConnection", createStateChangeArray(data.iceConnectionStates, dateFormat))
            add("signaling", createStateChangeArray(data.signalingStates, dateFormat))
        }
        stats.add("stateTransitions", stateTransitions)

        // ICE candidates with counts
        stats.add("iceCandidates", createIceCandidatesStatsJson(data, dateFormat))

        // ICE candidate errors (placeholder for future use)
        stats.add("iceCandidateErrors", JsonArray())

        // Media statistics
        stats.add("media", createMediaJson(data.mediaStats))

        // Media events
        stats.add("mediaEvents", createMediaEventsJson(data, dateFormat))

        dataWrapper.add("stats", stats)
        json.add("data", dataWrapper)

        return json
    }

    private fun createCandidateJson(candidate: CandidateDetails): JsonObject {
        return JsonObject().apply {
            addProperty("candidateType", candidate.candidateType)
            addProperty("protocol", candidate.protocol)
            addProperty("address", candidate.ip)
            addProperty("port", candidate.port)
            candidate.priority?.let { addProperty("priority", it) }
            candidate.relatedAddress?.let { addProperty("relatedAddress", it) }
            candidate.relatedPort?.let { addProperty("relatedPort", it) }
        }
    }

    private fun createStateChangeArray(
        states: List<StateChange>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            states.forEach { state ->
                add(JsonObject().apply {
                    addProperty("state", state.state)
                    addProperty("timestamp", dateFormat.format(Date(state.timestamp)))
                })
            }
        }
    }

    private fun createConnectionTimelineArray(
        events: List<ConnectionStateEvent>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            events.forEach { event ->
                add(JsonObject().apply {
                    addProperty("event", event.getDisplayName())
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
        }
    }

    private fun createIceCandidatesArray(
        candidates: List<IceCandidateInfo>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            candidates.forEach { candidate ->
                add(JsonObject().apply {
                    addProperty("type", candidate.type)
                    addProperty("protocol", candidate.protocol)
                    addProperty("timestamp", dateFormat.format(Date(candidate.timestamp)))
                })
            }
        }
    }

    private fun createAudioDeviceEventsArray(
        events: List<AudioDeviceEvent>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            events.forEach { event ->
                add(JsonObject().apply {
                    addProperty("event", event.event)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
        }
    }

    private fun createMicrophoneErrorsArray(
        errors: List<MicrophoneError>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            errors.forEach { error ->
                add(JsonObject().apply {
                    addProperty("error", error.error)
                    addProperty("timestamp", dateFormat.format(Date(error.timestamp)))
                })
            }
        }
    }

    private fun createTrackStateChangesArray(
        changes: List<TrackStateChange>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            changes.forEach { change ->
                add(JsonObject().apply {
                    addProperty("trackType", change.trackType)
                    addProperty("state", change.state)
                    addProperty("timestamp", dateFormat.format(Date(change.timestamp)))
                })
            }
        }
    }

    private fun createGetUserMediaEventsArray(
        events: List<GetUserMediaEvent>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            events.forEach { event ->
                add(JsonObject().apply {
                    addProperty("trackId", event.trackId)
                    addProperty("state", event.state)
                    addProperty("enabled", event.enabled)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
        }
    }

    private fun createSpeakerOutputEventsArray(
        events: List<SpeakerOutputEvent>,
        dateFormat: SimpleDateFormat
    ): JsonArray {
        return JsonArray().apply {
            events.forEach { event ->
                add(JsonObject().apply {
                    addProperty("device", event.device)
                    addProperty("isActive", event.isActive)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
        }
    }

    /**
     * Creates the ICE candidates stats JSON object with counts and candidates array.
     */
    private fun createIceCandidatesStatsJson(
        data: CallDebugData,
        dateFormat: SimpleDateFormat
    ): JsonObject {
        val candidatesByType = data.iceCandidates.groupBy { it.type.lowercase() }

        return JsonObject().apply {
            addProperty("total", data.iceCandidates.size)
            addProperty("hostCount", candidatesByType["host"]?.size ?: 0)
            addProperty("srflxCount", candidatesByType["srflx"]?.size ?: 0)
            addProperty("relayCount", candidatesByType["relay"]?.size ?: 0)

            // Selected candidate pair
            data.selectedCandidatePair?.let { pair ->
                val selectedPair = JsonObject().apply {
                    add("local", createCandidateJson(pair.local))
                    add("remote", createCandidateJson(pair.remote))
                }
                add("selectedPair", selectedPair)
            }

            // All candidates array
            add("candidates", createIceCandidatesArray(data.iceCandidates, dateFormat))
        }
    }

    /**
     * Creates the media statistics JSON object with outbound, inbound, and RTT.
     */
    private fun createMediaJson(stats: MediaStats): JsonObject {
        return JsonObject().apply {
            // Outbound stats
            val outbound = JsonObject().apply {
                addProperty("packetsSent", stats.outboundPacketsSent)
                addProperty("bytesSent", stats.outboundBytesSent)
                addProperty("audioEnergy", stats.outboundAudioEnergy)
            }
            add("outbound", outbound)

            // Inbound stats
            val inbound = JsonObject().apply {
                addProperty("packetsReceived", stats.inboundPacketsReceived)
                addProperty("bytesReceived", stats.inboundBytesReceived)
                addProperty("packetsLost", stats.inboundPacketsLost)
                addProperty("jitterMs", stats.inboundJitter)
                addProperty("audioLevel", stats.inboundAudioLevel)

                if (stats.inboundPacketsReceived > 0) {
                    val totalPackets = stats.inboundPacketsReceived + stats.inboundPacketsLost
                    val lossPercentage = (stats.inboundPacketsLost.toDouble() / totalPackets) * PERCENT_FACTOR
                    addProperty("packetLossPercentage", lossPercentage)
                }
            }
            add("inbound", inbound)

            addProperty("roundTripTimeMs", stats.roundTripTime)
        }
    }

    /**
     * Creates the media events JSON object with track changes, audio device changes, and microphone errors.
     */
    private fun createMediaEventsJson(
        data: CallDebugData,
        dateFormat: SimpleDateFormat
    ): JsonObject {
        return JsonObject().apply {
            // Track changes (combine trackStateChanges and getUserMediaEvents)
            val trackChanges = JsonArray()
            data.trackStateChanges.forEach { change ->
                trackChanges.add(JsonObject().apply {
                    addProperty("trackType", change.trackType)
                    addProperty("state", change.state)
                    addProperty("timestamp", dateFormat.format(Date(change.timestamp)))
                })
            }
            data.getUserMediaEvents.forEach { event ->
                trackChanges.add(JsonObject().apply {
                    addProperty("trackId", event.trackId)
                    addProperty("state", event.state)
                    addProperty("enabled", event.enabled)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
            add("trackChanges", trackChanges)

            // Audio device changes (combine audioDeviceEvents and speakerOutputEvents)
            val audioDeviceChanges = JsonArray()
            data.audioDeviceEvents.forEach { event ->
                audioDeviceChanges.add(JsonObject().apply {
                    addProperty("event", event.event)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
            data.speakerOutputEvents.forEach { event ->
                audioDeviceChanges.add(JsonObject().apply {
                    addProperty("device", event.device)
                    addProperty("isActive", event.isActive)
                    addProperty("timestamp", dateFormat.format(Date(event.timestamp)))
                })
            }
            add("audioDeviceChanges", audioDeviceChanges)

            // Microphone errors
            add("microphoneErrors", createMicrophoneErrorsArray(data.microphoneErrors, dateFormat))
        }
    }

    /**
     * Saves the call statistics to a JSON file in the app's cache directory.
     *
     * @param data The call debug data to save
     */
    private fun saveCallStatsToJsonFile(data: CallDebugData) {
        try {
            val callStatsDir = File(context.cacheDir, CALL_STATS_DIR)
            if (!callStatsDir.exists()) {
                callStatsDir.mkdirs()
            }

            val fileName = "$JSON_FILE_PREFIX${data.callId}$JSON_FILE_EXTENSION"
            val file = File(callStatsDir, fileName)

            val jsonObject = convertToJson(data)
            val jsonString = gson.toJson(jsonObject)

            file.writeText(jsonString)
            lastGeneratedJsonFilePath = file.absolutePath
            
            Timber.tag(TAG).d("Call stats JSON saved to: ${file.absolutePath}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save call stats JSON file")
        }
    }

    @Suppress("ComplexMethod")
    private fun logCallDebugData(data: CallDebugData) {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val duration = data.endTimestamp?.let { (it - data.startTimestamp) / MILLIS_PER_SECOND.toDouble() } ?: 0.0

        val logBuilder = StringBuilder()
        logBuilder.appendLine()
        logBuilder.appendLine(LOG_SEPARATOR)
        logBuilder.appendLine("CALL DEBUG DATA REPORT")
        logBuilder.appendLine(LOG_SEPARATOR)

        // Basic Identifiers
        logBuilder.appendLine()
        logBuilder.appendLine("BASIC IDENTIFIERS")
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
        logBuilder.appendLine("DEVICE & NETWORK")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Device:            ${data.deviceModel}")
        logBuilder.appendLine("  OS:                ${data.osVersion}")
        logBuilder.appendLine("  Network Type:      ${data.networkType}")

        // Permissions
        logBuilder.appendLine()
        logBuilder.appendLine("PERMISSIONS")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  RECORD_AUDIO:      ${if (data.recordAudioPermissionGranted) "GRANTED" else "DENIED"}")
        logBuilder.appendLine("  POST_NOTIFICATIONS: ${if (data.postNotificationsPermissionGranted) "GRANTED" else "DENIED"}")

        // Connection Timeline (ICE & DTLS)
        if (data.connectionTimeline.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("CONNECTION TIMELINE (ICE & DTLS)")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.connectionTimeline.forEach { event ->
                logBuilder.appendLine("  ${dateFormat.format(Date(event.timestamp))}: ${event.getDisplayName()}")
            }
        }

        // Legacy State Tracking
        logBuilder.appendLine()
        logBuilder.appendLine("LEGACY CONNECTION STATES")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Last ICE Gathering State:   ${data.lastIceGatheringState ?: "N/A"}")
        logBuilder.appendLine("  Last ICE Connection State:  ${data.lastIceConnectionState ?: "N/A"}")
        logBuilder.appendLine("  Last DTLS State:            ${data.lastDtlsState ?: "N/A"}")
        logBuilder.appendLine("  Last Signaling State:       ${data.lastSignalingState ?: "N/A"}")

        // Legacy State Change History (Deprecated)
        if (data.iceGatheringStates.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("  ICE Gathering State History:")
            data.iceGatheringStates.forEach { change ->
                logBuilder.appendLine("    ${dateFormat.format(Date(change.timestamp))}: ${change.state}")
            }
        }

        if (data.iceConnectionStates.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("  ICE Connection State History:")
            data.iceConnectionStates.forEach { change ->
                logBuilder.appendLine("    ${dateFormat.format(Date(change.timestamp))}: ${change.state}")
            }
        }

        // ICE Candidates
        logBuilder.appendLine()
        logBuilder.appendLine("ICE CANDIDATES")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Total Candidates:  ${data.iceCandidates.size}")
        if (data.iceCandidates.isNotEmpty()) {
            val candidatesByType = data.iceCandidates.groupBy { it.type }
            candidatesByType.forEach { (type, candidates) ->
                logBuilder.appendLine("    $type: ${candidates.size}")
            }
        }

        logBuilder.appendLine("  Selected Pair:")
        data.selectedCandidatePair?.let { pair ->
            logBuilder.appendLine("    Local Candidate:")
            logBuilder.appendLine("      Type:        ${pair.local.candidateType}")
            logBuilder.appendLine("      Protocol:    ${pair.local.protocol}")
            logBuilder.appendLine("      IP:          ${pair.local.ip}")
            logBuilder.appendLine("      Port:        ${pair.local.port}")
            if (pair.local.priority != null) {
                logBuilder.appendLine("      Priority:    ${pair.local.priority}")
            }
            if (pair.local.relatedAddress != null && pair.local.relatedPort != null) {
                logBuilder.appendLine("      Related:     ${pair.local.relatedAddress}:${pair.local.relatedPort}")
            }
            logBuilder.appendLine()
            logBuilder.appendLine("    Remote Candidate:")
            logBuilder.appendLine("      Type:        ${pair.remote.candidateType}")
            logBuilder.appendLine("      Protocol:    ${pair.remote.protocol}")
            logBuilder.appendLine("      IP:          ${pair.remote.ip}")
            logBuilder.appendLine("      Port:        ${pair.remote.port}")
            if (pair.remote.priority != null) {
                logBuilder.appendLine("      Priority:    ${pair.remote.priority}")
            }
            if (pair.remote.relatedAddress != null && pair.remote.relatedPort != null) {
                logBuilder.appendLine("      Related:     ${pair.remote.relatedAddress}:${pair.remote.relatedPort}")
            }
        } ?: run {
            logBuilder.appendLine("    No candidates collected, connection never established")
        }

        // Codec
        logBuilder.appendLine()
        logBuilder.appendLine("CODEC")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        logBuilder.appendLine("  Selected Codec:    ${data.selectedCodec ?: "N/A"}")

        // Media Statistics
        logBuilder.appendLine()
        logBuilder.appendLine("MEDIA STATISTICS")
        logBuilder.appendLine(LOG_SECTION_SEPARATOR)
        val stats = data.mediaStats
        if (stats.outboundPacketsSent > 0 || stats.inboundPacketsReceived > 0) {
            logBuilder.appendLine("  Outbound RTP:")
            logBuilder.appendLine("    Packets Sent:    ${stats.outboundPacketsSent}")
            logBuilder.appendLine("    Bytes Sent:      ${stats.outboundBytesSent}")
            logBuilder.appendLine("    Audio Energy:    ${String.format(Locale.US, "%.6f", stats.outboundAudioEnergy)}")
            logBuilder.appendLine()
            logBuilder.appendLine("  Inbound RTP:")
            logBuilder.appendLine("    Packets Received: ${stats.inboundPacketsReceived}")
            logBuilder.appendLine("    Bytes Received:   ${stats.inboundBytesReceived}")
            logBuilder.appendLine("    Packets Lost:     ${stats.inboundPacketsLost}")

            // Calculate packet loss percentage if we have received packets
            if (stats.inboundPacketsReceived > 0) {
                val totalPackets = stats.inboundPacketsReceived + stats.inboundPacketsLost
                val lossPercentage = (stats.inboundPacketsLost.toDouble() / totalPackets.toDouble()) * PERCENT_FACTOR
                logBuilder.appendLine("    Packet Loss:      ${String.format(Locale.US, "%.2f", lossPercentage)}%")
            }

            logBuilder.appendLine("    Jitter:           ${String.format(Locale.US, "%.3f", stats.inboundJitter)} ms")
            logBuilder.appendLine("    Audio Level:      ${String.format(Locale.US, "%.6f", stats.inboundAudioLevel)}")
            logBuilder.appendLine()
            logBuilder.appendLine("  Connection Quality:")
            logBuilder.appendLine("    Round Trip Time:  ${String.format(Locale.US, "%.3f", stats.roundTripTime)} ms")
        } else {
            logBuilder.appendLine("  No media statistics available")
        }

        // Audio Device Events
        if (data.audioDeviceEvents.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("AUDIO DEVICE EVENTS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.audioDeviceEvents.forEach { event ->
                logBuilder.appendLine("  ${dateFormat.format(Date(event.timestamp))}: ${event.event}")
            }
        }

        // getUserMedia Events
        if (data.getUserMediaEvents.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("GET USER MEDIA EVENTS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.getUserMediaEvents.forEach { event ->
                logBuilder.appendLine("  ${dateFormat.format(Date(event.timestamp))}: ID: ${event.trackId}, State: ${event.state}, Enabled: ${event.enabled}")
            }
        }

        // Microphone Errors
        if (data.microphoneErrors.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("MICROPHONE ERRORS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.microphoneErrors.forEach { error ->
                logBuilder.appendLine("  ${dateFormat.format(Date(error.timestamp))}: ${error.error}")
            }
        }

        // Track State Changes
        if (data.trackStateChanges.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("TRACK STATE CHANGES")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.trackStateChanges.forEach { change ->
                logBuilder.appendLine("  ${dateFormat.format(Date(change.timestamp))}: ${change.trackType} -> ${change.state}")
            }
        }

        // Speaker Output Events
        if (data.speakerOutputEvents.isNotEmpty()) {
            logBuilder.appendLine()
            logBuilder.appendLine("SPEAKER OUTPUT EVENTS")
            logBuilder.appendLine(LOG_SECTION_SEPARATOR)
            data.speakerOutputEvents.forEach { event ->
                val status = if (event.isActive) "ACTIVE" else "INACTIVE"
                logBuilder.appendLine("  ${dateFormat.format(Date(event.timestamp))}: ${event.device} -> $status")
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
     * Checks if a specific permission is granted.
     */
    private fun checkPermission(permission: String): Boolean {
        return try {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error checking permission: $permission")
            false
        }
    }

    /**
     * Clears all debug data. Should be called when the TelnyxClient is destroyed.
     */
    fun clear() {
        callDebugData.clear()
        callStatsUploader?.destroy()
        callStatsUploader = null
    }

    /**
     * Configures the upload settings for call statistics.
     * When enabled, call stats will be uploaded to the Telnyx call report endpoint
     * when a call ends.
     *
     * @param callReportId The call report ID token received from the REGED response
     * @param voiceSDKID The WebSocket session ID
     * @param uploadEnabled Whether upload is enabled (controlled by debug flag)
     */
    fun setUploadConfig(
        callReportId: String?,
        voiceSDKID: String?,
        uploadEnabled: Boolean
    ) {
        this.callReportId = callReportId
        this.voiceSDKID = voiceSDKID
        this.uploadEnabled = uploadEnabled
        if (uploadEnabled && callStatsUploader == null) {
            callStatsUploader = CallStatsUploader()
        }
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

    // Permissions
    val recordAudioPermissionGranted: Boolean,
    val postNotificationsPermissionGranted: Boolean,

    // State tracking
    val iceGatheringStates: MutableList<StateChange> = mutableListOf(),
    val iceConnectionStates: MutableList<StateChange> = mutableListOf(),
    val connectionTimeline: MutableList<ConnectionStateEvent> = mutableListOf(),
    val signalingStates: MutableList<StateChange> = mutableListOf(),
    var lastIceGatheringState: String? = null,
    var lastIceConnectionState: String? = null,
    var lastSignalingState: String? = null,
    var lastDtlsState: String? = null,
    var iceConnectedTimestamp: Long? = null,
    var dtlsConnectedTimestamp: Long? = null,

    // ICE candidates
    val iceCandidates: MutableList<IceCandidateInfo> = mutableListOf(),
    var selectedCandidatePair: CandidatePairInfo? = null,

    // Codec
    var selectedCodec: String? = null,

    // Media stats (accumulated totals)
    var mediaStats: MediaStats = MediaStats(),

    // Tracking for average calculations
    var jitterSum: Double = 0.0,
    var audioLevelSum: Double = 0.0,
    var rttSum: Double = 0.0,
    var statsUpdateCount: Int = 0,

    // Audio device events
    val audioDeviceEvents: MutableList<AudioDeviceEvent> = mutableListOf(),
    val microphoneErrors: MutableList<MicrophoneError> = mutableListOf(),
    val trackStateChanges: MutableList<TrackStateChange> = mutableListOf(),
    val getUserMediaEvents: MutableList<GetUserMediaEvent> = mutableListOf(),
    val speakerOutputEvents: MutableList<SpeakerOutputEvent> = mutableListOf()
)

/**
 * Represents a state change with timestamp.
 */
internal data class StateChange(
    val state: String,
    val timestamp: Long
)

/**
 * Sealed class representing connection state events with timestamps.
 * Tracks ICE and DTLS states separately for better diagnostics.
 */
sealed class ConnectionStateEvent(
    open val timestamp: Long
) {
    data class IceChecking(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class IceConnected(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class IceCompleted(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class IceDisconnected(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class IceFailed(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class IceClosed(override val timestamp: Long) : ConnectionStateEvent(timestamp)

    data class DtlsConnecting(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class DtlsConnected(override val timestamp: Long) : ConnectionStateEvent(timestamp)
    data class DtlsFailed(override val timestamp: Long, val reason: String) : ConnectionStateEvent(timestamp)

    data class ConnectionFailed(override val timestamp: Long, val reason: String) : ConnectionStateEvent(timestamp)

    fun getDisplayName(): String = when (this) {
        is IceChecking -> "ICE_CHECKING"
        is IceConnected -> "ICE_CONNECTED"
        is IceCompleted -> "ICE_COMPLETED"
        is IceDisconnected -> "ICE_DISCONNECTED"
        is IceFailed -> "ICE_FAILED"
        is IceClosed -> "ICE_CLOSED"
        is DtlsConnecting -> "DTLS_CONNECTING"
        is DtlsConnected -> "DTLS_CONNECTED"
        is DtlsFailed -> "DTLS_FAILED: $reason"
        is ConnectionFailed -> "CONNECTION_FAILED: $reason"
    }
}

/**
 * Represents ICE candidate information.
 */
internal data class IceCandidateInfo(
    val type: String,
    val protocol: String,
    val timestamp: Long
)

/**
 * Represents detailed information about an ICE candidate.
 */
data class CandidateDetails(
    val candidateType: String,
    val protocol: String,
    val ip: String,
    val port: Int,
    val priority: Long? = null,
    val relatedAddress: String? = null,
    val relatedPort: Int? = null
)

/**
 * Represents the selected ICE candidate pair with full details.
 */
internal data class CandidatePairInfo(
    val local: CandidateDetails,
    val remote: CandidateDetails
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
 * Represents a microphone error event with timestamp.
 */
internal data class MicrophoneError(
    val error: String,
    val timestamp: Long
)

/**
 * Represents a getUserMedia attempt (audio track creation).
 */
internal data class GetUserMediaEvent(
    val trackId: String,
    val state: String,
    val enabled: Boolean,
    val timestamp: Long
)

/**
 * Represents a permission change event.
 */
internal data class PermissionEvent(
    val permission: String,
    val granted: Boolean,
    val timestamp: Long
)

/**
 * Represents a speaker/output device change event.
 */
internal data class SpeakerOutputEvent(
    val device: String,
    val isActive: Boolean,
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
