package com.telnyx.webrtc.sdk.stats

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.verto.send.InitiateOrStopStatPrams
import com.telnyx.webrtc.sdk.verto.send.StatPrams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import org.slf4j.MDC.put
import org.webrtc.IceCandidate
import timber.log.Timber
import java.util.*

sealed class StatsData {
    data class WebRTCEnvents(val stats: JsonObject) : StatsData()
    data class Stats(val stats: JsonObject) : StatsData()
    data class PeerEvent<T>(val statsType: PeerStatsType, val data: T?) : StatsData()

}

enum class PeerStatsType(val event: String) {
    SIGNALING_CHANGE("onsignalingstatechange"),
    ICE_GATHER_CHANGE("onicegatheringstatechange"),
    ON_ICE_CANDIDATE("contraindicate"),
    ON_ADD_TRACK("ontrack"),
    ON_RENEGOTIATION_NEEDED("onnegotiationneeded"),
    ON_DATA_CHANNEL("ondatachannel"),
    ON_ICE_CONNECTION_STATE_CHANGE("oniceconnectionstatechange"),
    ON_ICE_CANDIDATE_ERROR("onicecandidateerror"),
}

internal class WebRTCReporter(val socket: TxSocket, val connectionId: String, val peer: Peer) {

    companion object {
        private const val STATS_INTERVAL: Long = 2000L
        private const val STATS_INITIAL: Long = 0L
    }

    private var isDebugStats = false

    internal var debugStatsId = UUID.randomUUID()

    private var debugReportStarted = false

    val statsDataFlow: MutableStateFlow<StatsData> = MutableStateFlow(
        StatsData.PeerEvent(
            PeerStatsType.SIGNALING_CHANGE,
            JsonObject()
        )
    )

    val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    // var gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val timer = Timer()


    internal fun stopTimer() {
        //client.stopStats(debugStatsId)
        debugStatsId = null
        timer.cancel()
    }

    internal fun startStats(sessionId: UUID) {
        debugReportStarted = true
        val loginMessage = InitiateOrStopStatPrams(
            type = "debug_report_start",
            debugReportId = sessionId.toString(),
        )
        socket.send(loginMessage)
    }

    /**
     * Sends Logged webrtc stats to backend
     *
     * @param config, the TokenConfig used to log in
     * @see [TokenConfig]
     */
    internal fun sendStats(data: JsonObject, sessionId: UUID) {

        val loginMessage = StatPrams(
            debugReportId = sessionId.toString(),
            reportData = data
        )
        socket.send(loginMessage)

    }

    internal fun stopStats(sessionId: UUID) {
        debugReportStarted = false
        val loginMessage = InitiateOrStopStatPrams(
            debugReportId = sessionId.toString(),
        )
        socket.send(loginMessage)
    }

    private suspend fun observeStatsFlow() {
        statsDataFlow.collectLatest {

            when (it) {
                is StatsData.PeerEvent<*> -> {
                    Timber.tag("Stats").d("Peer Event: ${it.statsType}")
                    when (it.statsType) {
                        PeerStatsType.SIGNALING_CHANGE -> {
                            Timber.tag("Stats").d("Peer Event: ${it.statsType}")

                            val ld = JsonObject().apply {
                                put("sdp", peer.getLocalDescription().toString())
                                put("type", "offer")
                            }
                            val localDescription = JsonObject().apply {
                                addProperty("sdp", peer.getLocalDescription().toString())
                                addProperty("type", "offer")
                            }

                            //TODO check the type of the remote description
                            val remoteDescription = JsonObject().apply {
                                addProperty("sdp", peer.getRemoteDescription().toString())
                                addProperty("type", "offer")
                            }

                            val data = JsonObject().apply {
                                add("localDescription", localDescription)
                                add(
                                    "remoteDescription",
                                    remoteDescription
                                ) // Represent null in Gson
                                addProperty("signalingState", "have-local-offer")
                            }
                            val statsEvent =
                                StatsEvent(it.statsType.event, "connection", connectionId, data)
                            onStatsEvent(statsEvent)
                        }

                        PeerStatsType.ICE_GATHER_CHANGE -> {

                        }

                        PeerStatsType.ON_ICE_CANDIDATE -> {
                            if (it.data is IceCandidate) {
                                Timber.tag("Stats").d("Peer Event: ${it.statsType}")
                                val iceCandidate = it.data


                                val data = JsonObject().apply {
                                    put("candidate", iceCandidate.toString())
                                    put("sdpMLineIndex", iceCandidate.sdpMLineIndex.toString())
                                    put("sdpMid", iceCandidate.sdpMid)
                                    // TODO check if the usernameFragment is the right value android iceCandidate does not have it
                                    put("usernameFragment", iceCandidate.serverUrl)
                                }
                                val statsEvent =
                                    StatsEvent(it.statsType.event, "connection", connectionId, data)
                                onStatsEvent(statsEvent)
                            }

                        }

                        PeerStatsType.ON_ADD_TRACK -> {

                        }

                        PeerStatsType.ON_RENEGOTIATION_NEEDED -> {

                        }
                        PeerStatsType.ON_DATA_CHANNEL -> {}
                        PeerStatsType.ON_ICE_CONNECTION_STATE_CHANGE -> {}
                        PeerStatsType.ON_ICE_CANDIDATE_ERROR -> {}
                    }
                }

                is StatsData.Stats -> {
                }


                is StatsData.WebRTCEnvents -> {}
            }
        }
    }

    internal suspend fun startTimer() {
        if (!debugReportStarted){
            debugStatsId = UUID.randomUUID()
            debugReportStarted = true
            observeStatsFlow()
        }
        timer.schedule(object : TimerTask() {
            var statsData: JsonObject = JsonObject()
            val data: JsonObject = JsonObject()
            var audio: JsonObject = JsonObject()
            var inBoundStats: JsonArray = JsonArray()
            var outBoundStats: JsonArray = JsonArray()
            override fun run() {
                peer.peerConnection?.getStats {
                    Timber.tag("Stats").d("Stats Timer ${it.statsMap}")
                    it.statsMap.forEach { (key, value) ->
                        if (value.type == "inbound-rtp") {
                            val jsonInbound = gson.toJsonTree(value)
                            inBoundStats.add(jsonInbound)
                            audio.add("inBoundStats", inBoundStats)
                        }
                        if (value.type == "outbound-rtp") {
                            val jsonOutbound = gson.toJsonTree(value)
                            outBoundStats.add(jsonOutbound)
                            audio.add("outBoundStats", outBoundStats)
                        }
                        if (value.type == "candidate-pair") {
                            val jsonCandidate = gson.toJsonTree(value)
                            data.add("connection", jsonCandidate)
                        }
                    }
                }
                data.add("audio", audio)
                val statsEvent = StatsEvent("webrtc_stats", "connection", connectionId, data)
                sendStats(statsEvent.toJson(), debugStatsId)
            }
        }, STATS_INITIAL, STATS_INTERVAL)
    }


    private fun onStatsEvent(statsEvent: StatsEvent) {
        Timber.tag("Stats").d("Stats Event: ${statsEvent.toJson()}")
        sendStats(statsEvent.toJson(), debugStatsId)
    }
}