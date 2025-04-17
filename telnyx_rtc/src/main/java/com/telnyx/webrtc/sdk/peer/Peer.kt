/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import android.content.Context
import com.telnyx.webrtc.sdk.Config.DEFAULT_STUN
import com.telnyx.webrtc.sdk.Config.DEFAULT_TURN
import com.telnyx.webrtc.sdk.Config.PASSWORD
import com.telnyx.webrtc.sdk.Config.USERNAME
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.lib.AudioSource
import com.telnyx.webrtc.lib.AudioTrack
import com.telnyx.webrtc.lib.DataChannel
import com.telnyx.webrtc.lib.DefaultVideoDecoderFactory
import com.telnyx.webrtc.lib.DefaultVideoEncoderFactory
import com.telnyx.webrtc.lib.EglBase
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.MediaConstraints
import com.telnyx.webrtc.lib.MediaStream
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.PeerConnectionFactory
import com.telnyx.webrtc.lib.SdpObserver
import com.telnyx.webrtc.lib.SessionDescription
import timber.log.Timber
import java.util.*
import kotlin.concurrent.timerTask

/**
 * Peer class that represents a peer connection which is required to initiate a call.
 *
 * @param context the Context of the application
 */
internal class Peer(
    context: Context,
    val client: TelnyxClient,
    private val providedTurn: String = DEFAULT_TURN,
    private val providedStun: String = DEFAULT_STUN,
    private val callId: UUID,
    val onIceCandidateAdd: ((String) -> (Unit))? = null
) {

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
        private const val NEGOTIATION_TIMEOUT = 300L // 300ms timeout for negotiation
    }

    private var lastCandidateTime = System.currentTimeMillis()
    private var negotiationTimer: Timer? = null
    private var onNegotiationComplete: (() -> Unit)? = null

    private val rootEglBase: EglBase = EglBase.create()

    private val mediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }

    val iceServer = getIceServers()

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
        Logger.d(message = "Start collection of ice servers")
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
        Logger.d(message = "End collection of ice servers")
        return iceServers
    }

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    internal var peerConnection: PeerConnection? = null

    internal var peerConnectionObserver: PeerConnectionObserver? = null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            Logger.d(tag = "Observer", message = "Signaling State Change: $p0")
            peerConnectionObserver?.onSignalingChange(p0)
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            Logger.d(tag = "Observer", message = "ICE Connection State Change: $p0")
            peerConnectionObserver?.onIceConnectionChange(p0)
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Logger.d(tag = "Observer", message = "ICE Connection Receiving Change: $p0")
            peerConnectionObserver?.onIceConnectionReceivingChange(p0)
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Logger.d(tag = "Observer", message = "ICE Gathering State Change: $p0")
            peerConnectionObserver?.onIceGatheringChange(p0)
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            Logger.d(
                tag = "Observer",
                message = "Event-IceCandidate Generated from server: $candidate"
            )
            // Only add candidates that come from our STUN/TURN servers (non-local)
            candidate?.let {
                if (!it.serverUrl.isNullOrEmpty() && (it.serverUrl == providedStun || it.serverUrl == providedTurn)) {
                    Logger.d(
                        tag = "Observer",
                        message = "Valid ICE candidate generated from server: ${it.serverUrl}"
                    )
                    if (client.calls[callId]?.getCallState()?.value != CallState.ACTIVE) {
                        peerConnection?.addIceCandidate(it)
                        Logger.d(tag = "Observer", message = "ICE candidate added: $it")
                        onIceCandidateAdd?.invoke(it.serverUrl)
                        // Reset the negotiation timer when we receive a new candidate
                        lastCandidateTime = System.currentTimeMillis()
                    }
                } else {
                    Logger.d(tag = "Observer", message = "Ignoring local ICE candidate: $it")
                }
            }
            peerConnectionObserver?.onIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            Logger.d(tag = "Observer", message = "ICE Candidates Removed: $p0")
            peerConnectionObserver?.onIceCandidatesRemoved(p0)
        }

        override fun onAddStream(p0: MediaStream?) {
            Logger.d(tag = "Observer", message = "Stream Added: $p0")
            peerConnectionObserver?.onAddStream(p0)
        }

        override fun onRemoveStream(p0: MediaStream?) {
            Logger.d(tag = "Observer", message = "Stream Removed: $p0")
            peerConnectionObserver?.onRemoveStream(p0)
        }

        override fun onDataChannel(p0: DataChannel?) {
            Logger.d(tag = "Observer", message = "Data Channel: $p0")
            peerConnectionObserver?.onDataChannel(p0)
        }

        override fun onRenegotiationNeeded() {
            Logger.d(tag = "Observer", message = "Renegotiation Needed")
            peerConnectionObserver?.onRenegotiationNeeded()
        }
    }

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
     * @see [getIceServers]
     */
    private fun buildPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServer).apply {
            iceTransportsType = PeerConnection.IceTransportsType.NOHOST
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        return peerConnectionFactory.createPeerConnection(config, observer)
    }

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
    }

    /**
     * Initiates a call, creating an offer with a local SDP
     * The offer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.call(sdpObserver: SdpObserver) {
        createOffer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Logger.d(tag = "Call", message = "onSetFailure $p0")
                            }

                            override fun onSetSuccess() {
                                Logger.d(tag = "Call", message = "onSetSuccess")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                Logger.d(tag = "Call", message = "onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                Logger.d(tag = "Call", message = "onCreateFailure $p0")
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                }
            },
            mediaConstraints
        )
    }

    /**
     * Answers a received invitation, creating an answer with a local SDP
     * The answer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.answer(sdpObserver: SdpObserver) {
        createAnswer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Logger.d(tag = "Answer", message = "onSetFailure $p0")
                            }

                            override fun onSetSuccess() {
                                Logger.d(tag = "Answer", message = "onSetSuccess")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                Logger.d(tag = "Answer", message = "onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                Logger.d(tag = "Answer", message = "onCreateFailure $p0")
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                }
            },
            mediaConstraints
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
                    Logger.d(tag = "RemoteSessionReceived", message = "Set Failure $p0")
                }

                override fun onSetSuccess() {
                    Logger.d(tag = "RemoteSessionReceived", message = "Set Success")
                }

                override fun onCreateSuccess(p0: SessionDescription?) {
                    Logger.d(tag = "RemoteSessionReceived", message = "Create Success")
                }

                override fun onCreateFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0)
                    Logger.d(tag = "RemoteSessionReceived", message = "Create Failure p0")
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
     * Returns the current remote SDP
     * @return [SessionDescription]
     */
    fun getRemoteDescription(): SessionDescription? {
        return peerConnection?.remoteDescription
    }

    /**
     * Closes and disposes of current [PeerConnection]
     */
    fun disconnect() {
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null
        } catch (e: IllegalStateException) {
            Logger.e(message = e.toString())
        }
    }

    /**
     * Sets a callback to be invoked when ICE negotiation is complete
     */
    fun setOnNegotiationComplete(callback: () -> Unit) {
        onNegotiationComplete = callback
        startNegotiationTimer()
    }

    /**
     * Starts the negotiation timer that checks for ICE candidate timeout
     */
    private fun startNegotiationTimer() {
        stopNegotiationTimer()
        negotiationTimer = Timer()
        negotiationTimer?.schedule(
            timerTask {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastCandidate = currentTime - lastCandidateTime

                Logger.d(
                    tag = "NegotiationTimer",
                    message = "Time since last candidate: ${timeSinceLastCandidate}ms"
                )

                if (timeSinceLastCandidate >= NEGOTIATION_TIMEOUT) {
                    Logger.d(tag = "NegotiationTimer", message = "Negotiation timeout reached")
                    onNegotiationComplete?.invoke()
                    stopNegotiationTimer()
                }
            },
            NEGOTIATION_TIMEOUT, NEGOTIATION_TIMEOUT
        )
    }

    /**
     * Stops and cleans up the negotiation timer
     */
    private fun stopNegotiationTimer() {
        negotiationTimer?.cancel()
        negotiationTimer?.purge()
        negotiationTimer = null
    }

    /**
     * Cleans up resources when the peer is no longer needed
     */
    fun release() {
        stopNegotiationTimer()
        if (peerConnection != null) {
            disconnect()
            peerConnectionFactory.dispose()
        }
    }

    init {
        initPeerConnectionFactory(context)
        peerConnection = buildPeerConnection()
    }
}