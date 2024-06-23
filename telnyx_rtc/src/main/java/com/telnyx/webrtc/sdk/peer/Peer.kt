/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config.DEFAULT_STUN
import com.telnyx.webrtc.sdk.Config.DEFAULT_TURN
import com.telnyx.webrtc.sdk.Config.PASSWORD
import com.telnyx.webrtc.sdk.Config.USERNAME
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.socket.TxSocket
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber
import java.util.*


/**
 * Peer class that represents a peer connection which is required to initiate a call.
 *
 * @param context the Context of the application
 * @param client the TelnyxClient instance in use.
 * @param observer the [PeerConnection.Observer] which observes the the Peer Connection events including ICE candidate or Stream changes, etc.
 */
internal class Peer(
    context: Context,
    val client: TelnyxClient,
    private val providedTurn: String = DEFAULT_TURN,
    private val providedStun: String = DEFAULT_STUN,
    private val callId: String = "",
    observer: PeerConnection.Observer
) {

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
    }

    private val rootEglBase: EglBase = EglBase.create()


    private val iceServer = getIceServers()

    /**
     * Retrieves the IceServers built with the provided STUN and TURN servers
     *
     * @see [TxSocket]
     * @see [PeerConnection.IceServer]
     *
     * @return [List] of [PeerConnection.IceServer]
     */
    private fun getIceServers(): List<PeerConnection.IceServer> {
        val iceServers: MutableList<PeerConnection.IceServer> = ArrayList()
        Timber.e("start get ice server")
        iceServers.add(
            PeerConnection.IceServer.builder(providedStun).setUsername(USERNAME).setPassword(
                PASSWORD
            ).createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder(providedTurn).setUsername(USERNAME).setPassword(
                PASSWORD
            ).createIceServer()
        )
        Timber.e("end get ice server")
        return iceServers
    }

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private var peerConnection: PeerConnection? = null

    /**
     * Initiates our peer connection factory with the specified options
     * @param context the context
     */
    private fun initPeerConnectionFactory(context: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            // .setFieldTrials("WebRTC-IntelVP8/Enabled/")
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    /**
     * creates the PeerConnectionFactory
     * @see [PeerConnectionFactory]
     * @return [PeerConnectionFactory]
     */
    private fun buildPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory
            .builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(
                DefaultVideoEncoderFactory(
                    rootEglBase.eglBaseContext,
                    true,
                    true
                )
            )
            .setOptions(
                PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                }
            )
            .createPeerConnectionFactory()
    }

    /**
     * Builds the PeerConnection with the provided IceServers from the getIceServers method
     * @param observer, the [PeerConnection.Observer]
     * @see [getIceServers]
     */
    private fun buildPeerConnection(observer: PeerConnection.Observer) =
        peerConnectionFactory.createPeerConnection(
            iceServer,
            observer
        )

    /**
     * Starts local audio capture to be used during call
     * @see [AudioSource]
     * @see [AudioTrack]
     */
    fun startLocalAudioCapture() {
        val audioSource: AudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        val localAudioTrack = peerConnectionFactory.createAudioTrack(
            AUDIO_LOCAL_TRACK_ID,
            audioSource
        )
        val localStream = peerConnectionFactory.createLocalMediaStream(AUDIO_LOCAL_STREAM_ID)
        localAudioTrack.setEnabled(true)
        localAudioTrack.setVolume(1.0)
        localStream.addTrack(localAudioTrack)
        peerConnection?.addTrack(localAudioTrack)
        if (client.isStatsEnabled){
            startTimer()
        }
    }

    var gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val timer = Timer()
    var mainObject: JsonObject = JsonObject()
    var audio: JsonObject = JsonObject()
    var statsData: JsonObject = JsonObject()
    var inBoundStats: JsonArray = JsonArray()
    var outBoundStats: JsonArray = JsonArray()
    var candidateParis: JsonArray = JsonArray()

    private fun stopTimer() {
        if (!client.isStatsEnabled) return
        client.sendStats(mainObject)
        mainObject = JsonObject()
        timer.cancel()
    }

    private fun startTimer() {
        timer.schedule(object : TimerTask() {
            override fun run() {
                mainObject.addProperty("event", "stats")
                mainObject.addProperty("tag", "stats")
                mainObject.addProperty("peerId", "stats")
                mainObject.addProperty("connectionId", callId)
                peerConnection?.getStats {
                    it.statsMap.forEach { (key, value) ->
                        if (value.type == "inbound-rtp") {
                            val jsonInbound = gson.toJsonTree(value)
                            inBoundStats.add(jsonInbound)
                        }
                        if (value.type == "outbound-rtp") {
                            val jsonOutbound = gson.toJson(value)
                            outBoundStats.add(jsonOutbound)
                        }
                        if (value.type == "candidate-pair" && candidateParis.size() < 5) {
                            val jsonCandidatePair = gson.toJson(value)
                            candidateParis.add(jsonCandidatePair)
                        }

                    }
                }
                audio.add("inbound", inBoundStats)
                audio.add("outbound", outBoundStats)
                audio.add("candidatePair", candidateParis)
                statsData.add("audio", audio)
                mainObject.add("data", statsData)
                mainObject.addProperty("timestamp", System.currentTimeMillis())
                if (inBoundStats.size() > 0 && outBoundStats.size() > 0 && candidateParis.size() > 0) {
                    inBoundStats = JsonArray()
                    outBoundStats = JsonArray()
                    candidateParis = JsonArray()
                    statsData = JsonObject()
                    audio = JsonObject()
                    Timber.tag("Stats Inbound").d("Inbound: ${mainObject.toString()}")
                   // client.sendStats(mainObject.toString())
                }

            }
        }, 0, 5000)
    }


    /**
     * Initiates a call, creating an offer with a local SDP
     * The offer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.call(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        createOffer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Timber.tag("Call").d("onSetFailure [%s]", "$p0")
                            }

                            override fun onSetSuccess() {
                                Timber.tag("Call").d("onSetSuccess")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                Timber.tag("Call").d("onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                Timber.tag("Call").d("onCreateFailure [%s]", "$p0")
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                }
            },
            constraints
        )
    }

    /**
     * Answers a received invitation, creating an answer with a local SDP
     * The answer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.answer(sdpObserver: SdpObserver) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        createAnswer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Timber.tag("Answer").d("onSetFailure [%s]", "$p0")
                            }

                            override fun onSetSuccess() {
                                Timber.tag("Answer").d("onSetSuccess")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                Timber.tag("Answer").d("onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                Timber.tag("Answer").d("onCreateFailure [%s]", "$p0")
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                }
            },
            constraints
        )
    }

    /**
     * Initiates an offer by setting a local SDP
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [call]
     */
    fun createOfferForSdp(sdpObserver: SdpObserver) = peerConnection?.call(sdpObserver)

    /**
     * Answers an invitation by setting a local SDP
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [answer]
     */
    fun answer(sdpObserver: SdpObserver) = peerConnection?.answer(sdpObserver)

    /**
     * Sets the local SDP once a remote session has been received
     * @param sessionDescription, the provided [SessionDescription] that will attempt to be set.
     * @see [SessionDescription]
     */
    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0)
                    Timber.tag("RemoteSessionReceived").d("Set Failure [%s]", p0)
                }

                override fun onSetSuccess() {
                    Timber.tag("RemoteSessionReceived").d("Set Success")
                }

                override fun onCreateSuccess(p0: SessionDescription?) {
                    Timber.tag("RemoteSessionReceived").d("Create Success")
                }

                override fun onCreateFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0)
                    Timber.tag("RemoteSessionReceived").d("Create Failure [%s]", p0)
                }
            },
            sessionDescription
        )
    }

    /**
     * Adds an [IceCandidate] to the [PeerConnection]
     * @param iceCandidate, the [IceCandidate] that wil bee added to the [PeerConnection]
     * @see [IceCandidate]
     */
    fun addIceCandidate(iceCandidate: IceCandidate?) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    /**
     * Returns the current local SDP
     * @return [SessionDescription]
     */
    fun getLocalDescription(): SessionDescription? {
        return peerConnection?.localDescription
    }

    /**
     * Closes and disposes of current [PeerConnection]
     */
    fun disconnect() {
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    fun release() {
        stopTimer()
        if (peerConnection != null) {
            disconnect()
            peerConnectionFactory.dispose()
        }
    }

    init {
        initPeerConnectionFactory(context)
        peerConnection = buildPeerConnection(observer)
    }
}
