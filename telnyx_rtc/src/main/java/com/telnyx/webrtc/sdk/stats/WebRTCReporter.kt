package com.telnyx.webrtc.sdk.stats

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.peer.PeerConnectionObserver
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.send.InitiateOrStopStatPrams
import com.telnyx.webrtc.sdk.verto.send.StatPrams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RTCStats
import timber.log.Timber
import java.util.*

sealed class StatsData {
    data class WebRTCEvent(val stats: JsonObject) : StatsData()
    data class Stats(val stats: JsonObject) : StatsData()
    data class PeerEvent<T>(val statsType: WebRTCStatsEvent, val data: T?) : StatsData()

}

enum class WebRTCStatsEvent(val event: String) {
    SIGNALING_CHANGE("onsignalingstatechange"),
    ICE_GATHER_CHANGE("onicegatheringstatechange"),
    ON_ICE_CANDIDATE("onicecandidate"),
    ON_ADD_TRACK("ontrack"),
    ON_RENEGOTIATION_NEEDED("onnegotiationneeded"),
    ON_DATA_CHANNEL("ondatachannel"),
    ON_ICE_CONNECTION_STATE_CHANGE("oniceconnectionstatechange"),
    ON_ICE_CANDIDATE_ERROR("onicecandidateerror"),
    ADD_CONNECTION("addConnection"),
    STATS("stats")
}

enum class WebRTCStatsTag(val tag: String) {
    PEER("peer"),
    STATS("stats"),
    CONNECTION("connection"),
    TRACK("track"),
    DATACHANNEL("datachannel"),
    GETUSERMEDIA("getUserMedia")
}

internal class WebRTCReporter(val socket: TxSocket, val peerId: UUID, val connectionId: String?, val peer: Peer) {

    companion object {
        private const val STATS_INTERVAL: Long = 2000L
        private const val UFRAG_LABEL = "ufrag"
        private const val ONE_SEC = 1000.0
    }

    internal var debugStatsId = UUID.randomUUID()

    private var debugReportStarted = false

    private var debugReportJob: Job? = null

    val statsDataFlow: MutableSharedFlow<StatsData> = MutableSharedFlow()

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    internal fun startStats() {
        if (debugReportStarted)
            return

        debugReportStarted = true

        val debugStartMessage = InitiateOrStopStatPrams(
            type = "debug_report_start",
            debugReportId = debugStatsId.toString(),
        )
        socket.send(debugStartMessage)
        peer.peerConnectionObserver = PeerConnectionObserver(this)

        sendAddConnectionMessage()

        debugReportJob = CoroutineScope(Dispatchers.IO).launch {
            startTimer()
        }

    }

    internal fun stopStats() {
        debugReportJob?.cancel()

        val debugStopMessage = InitiateOrStopStatPrams(
            debugReportId = debugStatsId.toString(),
        )
        socket.send(debugStopMessage)

        debugStatsId = null

        debugReportStarted = false
    }

    internal fun pauseStats() {
        debugReportJob?.cancel()
    }
    internal fun onStatsDataEvent(event: StatsData) {
        CoroutineScope(Dispatchers.IO).launch {
            statsDataFlow.emit(event)
        }
    }

    private suspend fun observeStatsFlow() {
        statsDataFlow.collect {
            when (it) {
                is StatsData.PeerEvent<*> -> {
                    Timber.tag("Stats").d("Peer Event: ${it.statsType}")
                    when (it.statsType) {
                        WebRTCStatsEvent.SIGNALING_CHANGE -> {
                            processSignalingChange(it)
                        }

                        WebRTCStatsEvent.ICE_GATHER_CHANGE -> {
                            processIceGatherChange(it)
                        }

                        WebRTCStatsEvent.ON_ICE_CANDIDATE -> {
                            processOnIceCandidate(it)
                        }

                        WebRTCStatsEvent.ON_ADD_TRACK -> {

                        }

                        WebRTCStatsEvent.ON_RENEGOTIATION_NEEDED -> {
                            processOnRenegotationNeeded(it)
                        }
                        WebRTCStatsEvent.ON_DATA_CHANNEL -> {}
                        WebRTCStatsEvent.ON_ICE_CONNECTION_STATE_CHANGE -> {}
                        WebRTCStatsEvent.ON_ICE_CANDIDATE_ERROR -> {}
                        WebRTCStatsEvent.ADD_CONNECTION -> {}
                        WebRTCStatsEvent.STATS -> {}
                    }
                }

                is StatsData.Stats -> {
                }


                is StatsData.WebRTCEvent -> {
                    Timber.tag("Stats").d("WebRTC Event")
                    sendStats(it.stats)
                }
            }
        }
    }

    internal suspend fun startTimer() {

        CoroutineScope(Dispatchers.IO).launch {
            while(isActive) {
                peer.peerConnection?.getStats {
                    Timber.tag("Stats").d("Stats Timer ${it.statsMap}")

                    val statsData: JsonObject = JsonObject()
                    val data: JsonObject = JsonObject()
                    val audio: JsonObject = JsonObject()
                    val connectionCandidates: MutableMap<String, MutableMap<String, Any>> = mutableMapOf()
                    val inBoundStats: JsonArray = JsonArray()
                    val outBoundStats: JsonArray = JsonArray()

                    val outBoundsArray = mutableListOf<MutableMap<String, Any>>()

                    it.statsMap.forEach { (key, value) ->
                        when (value.type) {
                            "inbound-rtp" -> {
                                processInboundRtp(key, value, statsData, inBoundStats, audio)
                            }
                            "outbound-rtp" -> {
                                processOutboundRtp(key, value, statsData, outBoundsArray)
                            }
                            "candidate-pair" -> {
                                processCandidatePair(key, value, statsData, connectionCandidates)
                            }
                            else -> {
                                processStatsDataMember(key, value, statsData)
                            }
                        }
                    }

                    //complete data which has different struct at webrtc-debug
                    processOutbounds(outBoundsArray, statsData, outBoundStats)

                    //find proper connection object
                    statsData.get("T01")?.asJsonObject?.get("selectedCandidatePairId")?.let { selectedCandidatePairId ->
                        val connectionCandidateMap = connectionCandidates[selectedCandidatePairId.asString].apply {
                            this?.get("localCandidateId")?.let { localId ->
                                val local = statsData.get(localId.toString())
                                local.asJsonObject.addProperty("id", localId.toString())
                                this["local"] = local
                            }
                            this?.get("remoteCandidateId")?.let { remoteId ->
                                val remote = statsData.get(remoteId.toString())
                                remote.asJsonObject.addProperty("id", remoteId.toString())
                                this["remote"] = remote
                            }
                        }
                        data.add("connection", gson.toJsonTree(connectionCandidateMap))
                    }
                    audio.add("outbound", outBoundStats)
                    data.add("audio", audio)

                    val statsEvent = StatsEvent(WebRTCStatsEvent.STATS.event, WebRTCStatsTag.STATS.tag,
                        peerId.toString(), connectionId ?: "", data, statsData = statsData)
                    onStatsDataEvent(StatsData.WebRTCEvent(statsEvent.toJson()))
                }

                delay(STATS_INTERVAL)
            }
        }

        observeStatsFlow()
    }

    private fun processSignalingChange(peerEvent: StatsData.PeerEvent<*>) {
        Timber.tag("Stats").d("Peer Event: ${peerEvent.statsType}")

        val localDescription = JsonObject().apply {
            addProperty("sdp", peer.getLocalDescription()?.description)
            addProperty("type", peer.getLocalDescription()?.type?.canonicalForm())
        }

        val remoteDescription = JsonObject().apply {
            addProperty("sdp", peer.getRemoteDescription()?.description)
            addProperty("type", peer.getRemoteDescription()?.type?.canonicalForm())
        }

        val data = JsonObject().apply {
            add("localDescription", localDescription)
            add("remoteDescription", remoteDescription) // Represent null in Gson
            addProperty("signalingState", "have-local-offer")
        }
        val statsEvent =
            StatsEvent(peerEvent.statsType.event, WebRTCStatsTag.CONNECTION.tag,
                peerId.toString(), connectionId ?: "", data)
        onStatsEvent(statsEvent)
    }

    private fun processIceGatherChange(peerEvent: StatsData.PeerEvent<*>) {
        if (peerEvent.data is PeerConnection.IceGatheringState) {
            Timber.tag("Stats").d("Peer Event: ${peerEvent.statsType} ${peerEvent.data.name}")

            val statsEvent = StatsEvent(peerEvent.statsType.event, WebRTCStatsTag.CONNECTION.tag,
                peerId.toString(), connectionId ?: "", dataString = peerEvent.data.name.lowercase())
            onStatsEvent(statsEvent)
        }
    }

    private fun processOnIceCandidate(peerEvent: StatsData.PeerEvent<*>) {
        if (peerEvent.data is IceCandidate) {
            Timber.tag("Stats").d("Peer Event: ${peerEvent.statsType}")
            val iceCandidate = peerEvent.data


            val data = JsonObject().apply {
                add("candidate", gson.toJsonTree(iceCandidate.sdp))
                add("sdpMLineIndex", gson.toJsonTree(iceCandidate.sdpMLineIndex))
                add("sdpMid", gson.toJsonTree(iceCandidate.sdpMid))

                var ufrag = ""
                val ufragIndex = iceCandidate.sdp.indexOf(UFRAG_LABEL)
                if (ufragIndex > 0) {
                    ufrag = iceCandidate.sdp.substring(ufragIndex+UFRAG_LABEL.length + 1, ufragIndex + (2*UFRAG_LABEL.length))
                }
                add("usernameFragment", gson.toJsonTree(ufrag))
            }
            val statsEvent =
                StatsEvent(peerEvent.statsType.event, WebRTCStatsTag.CONNECTION.tag,
                    peerId.toString(), connectionId ?: "", data)
            onStatsEvent(statsEvent)
        }
    }

    private fun processOnRenegotationNeeded(peerEvent: StatsData.PeerEvent<*>) {
        val statsEvent =
            StatsEvent(peerEvent.statsType.event, WebRTCStatsTag.CONNECTION.tag,
                peerId.toString(), connectionId ?: "", dataString = "")
        onStatsEvent(statsEvent)
    }

    private fun processInboundRtp(key: String, value: RTCStats, statsData: JsonObject, inBoundStats: JsonArray, audio: JsonObject) {
        val dataMap = value.members.apply {
            this["id"] = value.id
            this["timestamp"] = value.timestampUs / ONE_SEC
            this["type"] = value.type

        }

        if (value.members["kind"]?.toString()?.equals("audio") == true) {
            statsData.add(key, gson.toJsonTree(dataMap))
            val jsonInbound = gson.toJsonTree(dataMap)
            inBoundStats.add(jsonInbound)
            audio.add("inbound", inBoundStats)
        }
    }

    private fun processOutboundRtp(key: String, value: RTCStats, statsData: JsonObject, outBoundsArray: MutableList<MutableMap<String, Any>>) {
        val dataMap = value.members.apply {
            this["id"] = value.id
            this["timestamp"] = value.timestampUs / ONE_SEC
            this["type"] = value.type
        }

        if (value.members["kind"]?.toString()?.equals("audio") == true) {
            statsData.add(key, gson.toJsonTree(dataMap))
            outBoundsArray.add(dataMap)
        }
    }

    private fun processCandidatePair(key: String, value: RTCStats, statsData: JsonObject, connectionCandidates: MutableMap<String, MutableMap<String, Any>>) {
        val dataMap = value.members.apply {
            this["id"] = value.id
            this["timestamp"] = value.timestampUs / ONE_SEC
            this["type"] = value.type
        }

        statsData.add(key, gson.toJsonTree(dataMap))
        connectionCandidates[value.id] = dataMap
    }

    private fun processStatsDataMember(key: String, value: RTCStats, statsData: JsonObject) {
        val dataMap = value.members.apply {
            this["id"] = value.id
            this["timestamp"] = value.timestampUs / ONE_SEC
            this["type"] = value.type
        }

        statsData.add(key, gson.toJsonTree(dataMap))
    }

    private fun processOutbounds(outBoundsArray: MutableList<MutableMap<String, Any>>, statsData: JsonObject, outBoundStats: JsonArray) {
        outBoundsArray.forEach { outBoundItem ->
            processOutboundItem(outBoundItem, statsData, outBoundStats)
        }
    }

    private fun processOutboundItem(outBoundItem: MutableMap<String, Any>, statsData: JsonObject, outBoundStats: JsonArray) {
        outBoundItem["mediaSourceId"]?.let { mediaSourceId ->
            (mediaSourceId as? String)?.let { mediaId ->
                statsData.get(mediaId)?.let { mediaSource ->
                    mediaSource.asJsonObject.addProperty("id", mediaId)
                    outBoundItem["track"] = mediaSource
                }
            }
        }

        val jsonOutbound = gson.toJsonTree(outBoundItem)
        outBoundStats.add(jsonOutbound)
    }

    private fun sendAddConnectionMessage() {
        val options = JsonObject().apply {
            add("peerId", gson.toJsonTree(peerId))
        }

        val peerConfiguration = JsonObject().apply {
            add("bundlePolicy", gson.toJsonTree("max-compat"))
            add("iceCandidatePoolSize", gson.toJsonTree("0"))

            val iceServers = mutableListOf<JsonObject>()
            peer.iceServer.forEach {
                iceServers.add(JsonObject().apply {
                    add("urls", gson.toJsonTree(it.urls))
                    add("username", gson.toJsonTree(it.username))
                })
            }

            add("iceServers", gson.toJsonTree(iceServers))
            add("iceTransportPolicy", gson.toJsonTree("all"))
            add("rtcpMuxPolicy", gson.toJsonTree("require"))
        }
        val data = JsonObject().apply {
            add("options", gson.toJsonTree(options))
            add("peerConfiguration", gson.toJsonTree(peerConfiguration))
        }
        Timber.d("debug_report_ ${data}")
        val statsEvent = StatsEvent(WebRTCStatsEvent.ADD_CONNECTION.event, WebRTCStatsTag.PEER.tag, peerId.toString(), connectionId ?: "", data)
        onStatsEvent(statsEvent)
    }

    private fun onStatsEvent(statsEvent: StatsEvent) {
        Timber.tag("Stats").d("Stats Event: ${statsEvent.toJson()}")
        sendStats(statsEvent.toJson())
    }

    private fun sendStats(data: JsonObject) {
        debugStatsId?.let {
            val statsMessage = StatPrams(
                debugReportId = debugStatsId.toString(),
                reportData = data
            )
            socket.send(statsMessage)
        }
    }
}
