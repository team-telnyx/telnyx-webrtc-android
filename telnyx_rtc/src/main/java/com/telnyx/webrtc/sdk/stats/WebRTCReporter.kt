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
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
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
        private const val STATS_INITIAL: Long = 0L
    }

    private var isDebugStats = false

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

        //ToDo(Rad): check sessionId and debugReportStarted to be sure that this object is not making any reports already
        debugReportJob = CoroutineScope(Dispatchers.IO).launch {
            startTimer()
        }

    }

    internal fun stopStats(sessionId: UUID) {
        //ToDo(Rad): check if sessionId == debugStatsId

        debugReportJob?.cancel()

        val debugStopMessage = InitiateOrStopStatPrams(
            debugReportId = debugStatsId.toString(),
        )
        socket.send(debugStopMessage)

        debugStatsId = null

        debugReportStarted = false
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
                            Timber.tag("Stats").d("Peer Event: ${it.statsType}")

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
                                StatsEvent(it.statsType.event, WebRTCStatsTag.CONNECTION.tag, peerId.toString(), connectionId ?: "", data)
                            onStatsEvent(statsEvent)
                        }

                        WebRTCStatsEvent.ICE_GATHER_CHANGE -> {
                            if (it.data is PeerConnection.IceGatheringState) {
                                Timber.tag("Stats").d("Peer Event: ${it.statsType} ${it.data.name}")

                                val statsEvent = StatsEvent(it.statsType.event, WebRTCStatsTag.CONNECTION.tag,
                                        peerId.toString(), connectionId ?: "", dataString = it.data.name.lowercase())
                                onStatsEvent(statsEvent)
                            }
                        }

                        WebRTCStatsEvent.ON_ICE_CANDIDATE -> {
                            if (it.data is IceCandidate) {
                                Timber.tag("Stats").d("Peer Event: ${it.statsType}")
                                val iceCandidate = it.data


                                val data = JsonObject().apply {
                                    add("candidate", gson.toJsonTree(iceCandidate.sdp))
                                    add("sdpMLineIndex", gson.toJsonTree(iceCandidate.sdpMLineIndex))
                                    add("sdpMid", gson.toJsonTree(iceCandidate.sdpMid))

                                    var ufrag = ""
                                    val ufragIndex = iceCandidate.sdp.indexOf("ufrag")
                                    if (ufragIndex > 0) {
                                        ufrag = iceCandidate.sdp.substring(ufragIndex+6, ufragIndex+10)
                                    }
                                    add("usernameFragment", gson.toJsonTree(ufrag))
                                }
                                val statsEvent =
                                    StatsEvent(it.statsType.event, WebRTCStatsTag.CONNECTION.tag, peerId.toString(), connectionId ?: "", data)
                                onStatsEvent(statsEvent)
                            }

                        }

                        WebRTCStatsEvent.ON_ADD_TRACK -> {

                        }

                        WebRTCStatsEvent.ON_RENEGOTIATION_NEEDED -> {
                            val statsEvent =
                                StatsEvent(it.statsType.event, WebRTCStatsTag.CONNECTION.tag, peerId.toString(), connectionId ?: "", dataString = "")
                            onStatsEvent(statsEvent)
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
                                val dataMap = value.members.apply {
                                    this["id"] = value.id
                                    this["timestamp"] = value.timestampUs / 1000.0
                                    this["type"] = value.type

                                }

                                if (value.members["kind"]?.toString()?.equals("audio") == true) {
                                    statsData.add(key, gson.toJsonTree(dataMap))
                                    val jsonInbound = gson.toJsonTree(dataMap)
                                    inBoundStats.add(jsonInbound)
                                    audio.add("inbound", inBoundStats)
                                }
                            }
                            "outbound-rtp" -> {
                                val dataMap = value.members.apply {
                                    this["id"] = value.id
                                    this["timestamp"] = value.timestampUs / 1000.0
                                    this["type"] = value.type
                                }

                                if (value.members["kind"]?.toString()?.equals("audio") == true) {
                                    statsData.add(key, gson.toJsonTree(dataMap))
                                    outBoundsArray.add(dataMap)
                                }
                            }
                            "candidate-pair" -> {
                                val dataMap = value.members.apply {
                                    this["id"] = value.id
                                    this["timestamp"] = value.timestampUs / 1000.0
                                    this["type"] = value.type
                                }

                                statsData.add(key, gson.toJsonTree(dataMap))
                                connectionCandidates[value.id] = dataMap
                            }
                            else -> {
                                val dataMap = value.members.apply {
                                    this["type"] = value.type
                                }

                                statsData.add(key, gson.toJsonTree(dataMap))
                            }
                        }
                    }

                    //complete data which has different struct at webrtc-debug
                    outBoundsArray.forEach { outBoundItem ->
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

                    val statsEvent = StatsEvent(WebRTCStatsEvent.STATS.event, WebRTCStatsTag.STATS.tag, peerId.toString(), connectionId ?: "", data, statsData = statsData)
                    onStatsDataEvent(StatsData.WebRTCEvent(statsEvent.toJson()))
                }

                delay(STATS_INTERVAL)
            }
        }

        observeStatsFlow()
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
            /*put("candidate", iceCandidate.toString())
            put("sdpMLineIndex", iceCandidate.sdpMLineIndex.toString())
            put("sdpMid", iceCandidate.sdpMid)
            // TODO check if the usernameFragment is the right value android iceCandidate does not have it
            put("usernameFragment", iceCandidate.serverUrl)*/
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
        val statsMessage = StatPrams(
            debugReportId = debugStatsId.toString(),
            reportData = data
        )
        socket.send(statsMessage)
    }
}