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
import com.telnyx.webrtc.lib.RtpTransceiver
import com.telnyx.webrtc.lib.MediaStreamTrack
import kotlinx.coroutines.CompletableDeferred
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
    private val prefetchIceCandidate: Boolean = false,
    private val forceRelayCandidate: Boolean = false,
    val onIceCandidateAdd: ((String) -> (Unit))? = null
) {

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
        private const val NEGOTIATION_TIMEOUT = 300L // 300ms timeout for negotiation
        private const val ENABLE_PREFETCH_CANDIDATES = 10
        private const val DISABLE_PREFETCH_CANDIDATES = 0
    }

    private var lastCandidateTime = System.currentTimeMillis()
    private var negotiationTimer: Timer? = null
    private var onNegotiationComplete: (() -> Unit)? = null

    // Deferred to signal when the first ICE candidate (local or remote) is processed
    internal val firstCandidateDeferred = CompletableDeferred<Unit>()
    private var firstCandidateReceived = false // Flag to ensure deferred completes only once

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

    val iceCandidatePoolSize = getIceCandidatePool()

    /*
    * Returns the number of ICE candidates to prefetch
    *
    * @return [Int] size of the ice candidate pool
     */
    private fun getIceCandidatePool(): Int {
        return if (prefetchIceCandidate) ENABLE_PREFETCH_CANDIDATES else DISABLE_PREFETCH_CANDIDATES
    }

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    internal var peerConnection: PeerConnection? = null

    internal var peerConnectionObserver: PeerConnectionObserver? = null
    private var localAudioTrack: AudioTrack? = null

    private fun logAudioTrackAndTransceiverState(contextTag: String) {
        if (peerConnection == null) {
            Logger.w(tag = "Peer:AudioState", message = "$contextTag - PeerConnection is null.")
            return
        }
        Logger.d(tag = "Peer:AudioState", message = "$contextTag - Checking audio state...")
        val localTrackId = localAudioTrack?.id()
        Logger.d(tag = "Peer:AudioState", message = "$contextTag - LocalAudioTrack ID: $localTrackId, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}")

        val audioTransceiver = peerConnection?.transceivers?.find {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }

        if (audioTransceiver == null) {
            Logger.w(tag = "Peer:AudioState", message = "$contextTag - No audio transceiver found.")
        } else {
            val senderTrackId = audioTransceiver.sender.track()?.id()
            Logger.d(tag = "Peer:AudioState", message = "$contextTag - Audio Transceiver Found: Mid: ${audioTransceiver.mid}, Direction: ${audioTransceiver.direction}, CurrentDirection: ${audioTransceiver.currentDirection}, Sender Track ID: $senderTrackId, Receiver Track ID: ${audioTransceiver.receiver.track()?.id()}, Stopped: ${audioTransceiver.isStopped}")
            if (senderTrackId != null && localTrackId != null && senderTrackId != localTrackId) {
                 Logger.w(tag = "Peer:AudioState", message = "$contextTag - Audio transceiver sender track ID [$senderTrackId] does NOT match localAudioTrack ID [$localTrackId]")
            } else if (senderTrackId == null && localTrackId != null) {
                 Logger.w(tag = "Peer:AudioState", message = "$contextTag - Audio transceiver sender track is null, but localAudioTrack ID is [$localTrackId]")
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            Logger.d(tag = "Observer", message = "Signaling State Change: $p0")
            peerConnectionObserver?.onSignalingChange(p0)
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Logger.d(tag = "Observer", message = "ICE Connection State Change: $newState")
            peerConnectionObserver?.onIceConnectionChange(newState)

            if (newState == PeerConnection.IceConnectionState.CONNECTED || newState == PeerConnection.IceConnectionState.COMPLETED) {
                logAudioTrackAndTransceiverState("onIceConnectionChange ($newState)")
            }
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
            // Signal that the first candidate has been received (only once)
            if (!firstCandidateReceived) {
                firstCandidateReceived = true
                firstCandidateDeferred.complete(Unit)
                 Logger.d(tag = "Observer", message = "First ICE candidate processed, completing deferred.")
            }

            Logger.d(tag = "Observer", message = "Event-IceCandidate Generated: $candidate")
            candidate?.let {
                Logger.d(tag = "Observer", message = "Processing ICE candidate: ${it.serverUrl}")
                if (client.calls[callId]?.getCallState()?.value != CallState.ACTIVE) {
                    peerConnection?.addIceCandidate(it)
                    Logger.d(tag = "Observer", message = "ICE candidate added: $it")
                    onIceCandidateAdd?.invoke(it.serverUrl)
                    lastCandidateTime = System.currentTimeMillis()
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
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = getIceCandidatePool()
            
            // Control local network access for ICE candidate gathering
            if (forceRelayCandidate) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }

        return peerConnectionFactory.createPeerConnection(config, observer)
    }

    /**
     * Starts local audio capture to be used during call
     * @see [AudioSource]
     * @see [AudioTrack]
     * @see [RtpTransceiver]
     */
    fun startLocalAudioCapture() {
        Logger.d(tag = "Peer:Audio", message = "Attempting to start local audio capture...")
        val audioSource: AudioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack(
            AUDIO_LOCAL_TRACK_ID,
            audioSource
        )

        if (localAudioTrack == null) {
            Logger.e(tag = "Peer:Audio", message = "Failed to create local audio track.")
            return
        }

        localAudioTrack?.setEnabled(true)
        localAudioTrack?.setVolume(1.0)
        Logger.d(tag = "Peer:Audio", message = "Local audio track created. ID: ${localAudioTrack?.id()}, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}")

        val localStream = peerConnectionFactory.createLocalMediaStream(AUDIO_LOCAL_STREAM_ID)
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
        if (localAudioTrack == null) {
            Logger.w(tag = "Call", message = "Local audio track not initialized before creating offer.")
        }
        createOffer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Logger.e(tag="Call", message = "setLocalDescription onSetFailure $p0")
                            }

                            override fun onSetSuccess() {
                                Logger.d(tag="Call", message = "setLocalDescription onSetSuccess")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                Logger.d(tag = "Call", message = "createOffer onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                Logger.e(tag = "Call", message = "createOffer onCreateFailure $p0")
                                sdpObserver.onCreateFailure(p0)
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                    Logger.d(tag = "SDP", message = "[Local Offer SDP]:\n${desc?.description}")
                }

                override fun onCreateFailure(p0: String?) {
                    Logger.e(tag = "Call", message = "createOffer onCreateFailure $p0")
                    sdpObserver.onCreateFailure(p0)
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
        Logger.d(tag = "Answer", message = "Preparing to create answer...")

        Logger.d(tag = "Answer", message = "Adjusting transceiver directions before createAnswer...")
        this.transceivers.forEach { transceiver ->
            when (transceiver.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                         Logger.w(tag = "Answer", message = "Audio transceiver direction was ${transceiver.direction}. Setting to SEND_RECV.")
                         transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    } else {
                         Logger.d(tag = "Answer", message = "Audio transceiver direction already SEND_RECV.")
                    }
                }
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE) {
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                        Logger.d(tag = "Answer", message = "Setting video transceiver [Mid: ${transceiver.mid}] direction to INACTIVE")
                    } else {
                        Logger.d(tag = "Answer", message = "Video transceiver [Mid: ${transceiver.mid}] direction already INACTIVE.")
                    }
                }
                else -> {
                     Logger.d(tag = "Answer", message = "Ignoring transceiver with unknown media type: ${transceiver.mediaType}")
                }
            }
        }
        logAllTransceiverStates("Before createAnswer")

        createAnswer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    logAllTransceiverStates("After createAnswer success, before setLocalDescription")

                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Logger.e(tag="Answer", message = "setLocalDescription onSetFailure $p0")
                            }

                            override fun onSetSuccess() {
                                Logger.d(tag="Answer", message = "setLocalDescription onSetSuccess")
                                logAllTransceiverStates("After setLocalDescription success")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */ }
                            override fun onCreateFailure(p0: String?) { /* No-op */ }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                    Logger.d(tag = "SDP", message = "[Local Answer SDP]:\n${desc?.description}")
                }

                override fun onCreateFailure(p0: String?) {
                    Logger.e(tag = "Answer", message = "createAnswer onCreateFailure $p0")
                    sdpObserver.onCreateFailure(p0)
                }
            },
            mediaConstraints
        )
    }

    private fun logAllTransceiverStates(contextTag: String) {
         if (peerConnection == null) return
         Logger.d(tag = "TransceiverState", message = "--- Transceiver States [$contextTag] ---")
         peerConnection?.transceivers?.forEachIndexed { index, t ->
              Logger.d(tag = "TransceiverState", message = "[$contextTag] Transceiver[$index]: Mid=${t.mid}, MediaType=${t.mediaType}, Direction=${t.direction}, CurrentDirection=${t.currentDirection}, Stopped=${t.isStopped}, SenderTrack=${t.sender.track()?.id()}, ReceiverTrack=${t.receiver.track()?.id()}")
         }
         Logger.d(tag = "TransceiverState", message = "--- End Transceiver States [$contextTag] ---")
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
        Logger.d(tag = "SDP", message = "[Remote Offer/Answer SDP Received]:\n${sessionDescription.description}")
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0)
                    Logger.e(tag="RemoteSessionReceived", message = "Set Remote Description Failed: $p0")
                }

                override fun onSetSuccess() {
                    Logger.d(tag="RemoteSessionReceived", message = "Set Remote Description Success")
                    logAllTransceiverStates("After setRemoteDescription success")
                }

                override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */ }

                override fun onCreateFailure(p0: String?) {
                    Logger.e(tag="RemoteSessionReceived", message = "Set Remote Description reported onCreateFailure: $p0")
                    client.onRemoteSessionErrorReceived(p0)
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
        }catch (e: IllegalStateException){
            Logger.e(message = "Error during peer connection disconnect: $e")
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
        negotiationTimer?.cancel()
        negotiationTimer?.purge()

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
                    Logger.d(tag = "NegotiationTimer", message = "Negotiation timeout reached - Invoking onNegotiationComplete")
                    stopNegotiationTimer()
                    onNegotiationComplete?.invoke()
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
         Logger.d(tag = "NegotiationTimer", message = "Negotiation timer stopped.")
    }

    /**
     * Cleans up resources when the peer is no longer needed
     */
    fun release() {
        Logger.d(message="Releasing Peer resources...")
        stopNegotiationTimer()
        if (peerConnection != null) {
            disconnect()
        }
    }

    /**
     * Initializes the Peer with the provided context and builds the PeerConnection
     */
    init {
        initPeerConnectionFactory(context)
        peerConnection = buildPeerConnection()
        // Reset the flag when a new Peer is created
        firstCandidateReceived = false
    }
}
