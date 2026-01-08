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
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.AudioConstraints
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.SdpUtils
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.verto.send.CandidateParams
import com.telnyx.webrtc.sdk.verto.send.EndOfCandidatesParams
import com.telnyx.webrtc.sdk.verto.send.CandidateDialogParams
import com.telnyx.webrtc.sdk.utilities.CodecUtils
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
import com.telnyx.webrtc.lib.RtpCapabilities
import com.telnyx.webrtc.sdk.model.AudioCodec
import kotlinx.coroutines.CompletableDeferred
import java.util.*
import kotlin.concurrent.timerTask

/**
 * Peer class that represents a peer connection which is required to initiate a call.
 *
 * @param context the Context of the application
 * @param audioConstraints optional audio processing constraints for the call
 */
internal class Peer(
    context: Context,
    val client: TelnyxClient,
    private val providedTurn: String = DEFAULT_TURN,
    private val providedStun: String = DEFAULT_STUN,
    private val callId: UUID,
    private val prefetchIceCandidate: Boolean = false,
    private val forceRelayCandidate: Boolean = false,
    private val isAnswering: Boolean = false,
    val onIceCandidateAdd: ((String) -> (Unit))? = null,
    private val audioConstraints: AudioConstraints? = null
) {

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
        private const val NEGOTIATION_TIMEOUT = 300L // 300ms timeout for negotiation
        private const val END_OF_CANDIDATES_TIMEOUT =
            3000L // 3 seconds timeout for end-of-candidates
        private const val ENABLE_PREFETCH_CANDIDATES = 10
        private const val DISABLE_PREFETCH_CANDIDATES = 0

        // ICE renegotiation delay constants
        private const val ICE_RESTART_DELAY_MS = 500L // 0.5 second delay for ICE restart

        private const val AUDIO_BUFFER_RESET_DELAY_MS =
            200L // 0.2 second delay for audio buffer reset
        private const val AUDIO_RE_ENABLE_DELAY_MS =
            100L // 0.1 second delay before re-enabling audio


        // ICE candidate parsing constants
        private const val TYP_PREFIX = "typ "
        private const val PROTOCOL_INDEX = 2
        private const val UNKNOWN_VALUE = "unknown"

        /**
         * Shared PeerConnectionFactory used for codec queries and peer connections.
         * Lazily initialized on first access and shared across all Peer instances.
         * This is thread-safe and follows WebRTC best practices.
         */
        private val sharedPeerConnectionFactory: PeerConnectionFactory by lazy {
            buildSharedPeerConnectionFactory()
        }

        /**
         * Ensures PeerConnectionFactory is initialized with the application context.
         * This should be called before any WebRTC operations.
         * Safe to call multiple times - initialization only happens once.
         *
         * @param context Application context
         */
        private fun initPeerConnectionFactory(context: Context) {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
        }

        /**
         * Builds the shared PeerConnectionFactory with standard configuration.
         * This factory is used for both codec queries and actual peer connections.
         *
         * @return Configured PeerConnectionFactory instance
         */
        private fun buildSharedPeerConnectionFactory(): PeerConnectionFactory {
            val rootEglBase = EglBase.create()
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
         * Gets the list of audio codecs supported by WebRTC without creating a Peer instance.
         * This is an efficient way to query available codecs before making calls.
         *
         * @param context Application context
         * @return List of AudioCodec objects representing supported codecs
         */
        fun getSupportedAudioCodecs(context: Context): List<AudioCodec> {
            return try {
                // Ensure WebRTC is initialized
                initPeerConnectionFactory(context)

                // Query capabilities from shared factory
                val capabilities = sharedPeerConnectionFactory.getRtpSenderCapabilities(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                )

                Logger.d(
                    tag = "Peer.Companion",
                    message = "Retrieved ${capabilities.codecs.size} codec capabilities from shared factory"
                )

                // Convert to AudioCodec list
                CodecUtils.convertCapabilitiesToAudioCodecs(capabilities.codecs)
            } catch (e: Exception) {
                Logger.e(
                    tag = "Peer.Companion",
                    message = "Error retrieving supported audio codecs: ${e.message}"
                )
                emptyList()
            }
        }
    }

    private var lastCandidateTime = System.currentTimeMillis()
    private var negotiationTimer: Timer? = null
    private var onNegotiationComplete: (() -> Unit)? = null

    // Deferred to signal when the first ICE candidate (local or remote) is processed
    internal val firstCandidateDeferred = CompletableDeferred<Unit>()
    private var firstCandidateReceived = false // Flag to ensure deferred completes only once

    // Selective candidate queuing for answering side (until ANSWER is sent)
    private val queuedCandidates = mutableListOf<IceCandidate>()
    private var answerSent = false

    // End-of-candidates timer management
    private var endOfCandidatesTimer: Timer? = null
    private var endOfCandidatesSent = false

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

    /**
     * Use the shared PeerConnectionFactory for this Peer instance.
     * This improves efficiency by reusing the factory across all peers.
     */
    private val peerConnectionFactory: PeerConnectionFactory
        get() = sharedPeerConnectionFactory

    internal var peerConnection: PeerConnection? = null

    internal var peerConnectionObserver: PeerConnectionObserver? = null
    private var localAudioTrack: AudioTrack? = null
    private var previousIceConnectionState: PeerConnection.IceConnectionState? = null

    /**
     * Gets the supported audio codec capabilities from the shared factory.
     * This method is kept for backward compatibility with existing code.
     *
     * @return List of codec capabilities
     */
    internal fun getSupportedSenderAudioCodecs(): List<RtpCapabilities.CodecCapability> {
        val capabilities = peerConnectionFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
        return capabilities.codecs
    }

    private fun logAudioTrackAndTransceiverState(contextTag: String) {
        if (peerConnection == null) {
            Logger.w(tag = "Peer:AudioState", message = "$contextTag - PeerConnection is null.")
            return
        }
        Logger.d(tag = "Peer:AudioState", message = "$contextTag - Checking audio state...")
        val localTrackId = localAudioTrack?.id()
        Logger.d(
            tag = "Peer:AudioState",
            message = "$contextTag - LocalAudioTrack ID: $localTrackId, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}"
        )

        val audioTransceiver = peerConnection?.transceivers?.find {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }

        if (audioTransceiver == null) {
            Logger.w(tag = "Peer:AudioState", message = "$contextTag - No audio transceiver found.")
        } else {
            val senderTrackId = audioTransceiver.sender.track()?.id()
            Logger.d(
                tag = "Peer:AudioState",
                message = "$contextTag - Audio Transceiver Found: Mid: ${audioTransceiver.mid}, Direction: ${audioTransceiver.direction}, CurrentDirection: ${audioTransceiver.currentDirection}, Sender Track ID: $senderTrackId, Receiver Track ID: ${
                    audioTransceiver.receiver.track()?.id()
                }, Stopped: ${audioTransceiver.isStopped}"
            )
            if (senderTrackId != null && localTrackId != null && senderTrackId != localTrackId) {
                Logger.w(
                    tag = "Peer:AudioState",
                    message = "$contextTag - Audio transceiver sender track ID [$senderTrackId] does NOT match localAudioTrack ID [$localTrackId]"
                )
            } else if (senderTrackId == null && localTrackId != null) {
                Logger.w(
                    tag = "Peer:AudioState",
                    message = "$contextTag - Audio transceiver sender track is null, but localAudioTrack ID is [$localTrackId]"
                )
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            Logger.d(tag = "Observer", message = "Signaling State Change: $p0")
            peerConnectionObserver?.onSignalingChange(p0)
            // Notify debug data collector
            p0?.let { client.debugDataCollector.onSignalingStateChange(callId, it.name) }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            Logger.d(tag = "Observer", message = "ICE Connection State Change: $newState")
            peerConnectionObserver?.onIceConnectionChange(newState)
            // Notify debug data collector
            newState?.let { client.debugDataCollector.onIceConnectionStateChange(callId, it.name) }

            // Handle ICE connection state transitions
            handleIceConnectionStateTransition(previousIceConnectionState, newState)

            // Update previous state
            previousIceConnectionState = newState
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            Logger.d(tag = "Observer", message = "ICE Connection Receiving Change: $p0")
            peerConnectionObserver?.onIceConnectionReceivingChange(p0)
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            Logger.d(tag = "Observer", message = "ICE Gathering State Change: $p0")

            // Send end-of-candidates when ICE gathering is complete and trickle ICE is enabled
            if (p0 == PeerConnection.IceGatheringState.COMPLETE && client.getUseTrickleIce()) {
                sendEndOfCandidates()
                Logger.d(tag = "Observer", message = "End-of-candidates sent via trickle ICE")
            }

            peerConnectionObserver?.onIceGatheringChange(p0)
            // Notify debug data collector
            p0?.let { client.debugDataCollector.onIceGatheringStateChange(callId, it.name) }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            // Signal that the first candidate has been received (only once)
            if (!firstCandidateReceived) {
                firstCandidateReceived = true
                firstCandidateDeferred.complete(Unit)
                Logger.d(
                    tag = "Observer",
                    message = "First ICE candidate processed, completing deferred."
                )
            }

            Logger.d(tag = "Observer", message = "Event-IceCandidate Generated: $candidate")
            candidate?.let {
                Logger.d(tag = "Observer", message = "Processing ICE candidate: ${it.serverUrl}")
                if (client.getUseTrickleIce()) {
                    if (isAnswering && !answerSent) {
                        // Answering side: Queue candidate until ANSWER is sent
                        queuedCandidates.add(it)
                        Logger.d(
                            tag = "Observer",
                            message = "ICE candidate queued for answering side (ANSWER not sent yet): $it"
                        )
                    } else {
                        // Calling side OR answering side after ANSWER sent: Send immediately
                        sendIceCandidate(it)
                        Logger.d(
                            tag = "Observer",
                            message = "ICE candidate sent via trickle ICE: $it (isAnswering=$isAnswering, answerSent=$answerSent)"
                        )

                        // Start/restart the end-of-candidates timer when sending candidates
                        startEndOfCandidatesTimer()
                    }

                    // Notify debug data collector about ICE candidate
                    val candidateType = extractCandidateType(it.sdp)
                    val protocol = extractProtocol(it.sdp)
                    client.debugDataCollector.onIceCandidateAdded(callId, candidateType, protocol)
                } else {
                    // Traditional ICE: Only process if call is not ACTIVE or RENEGOTIATING yet
                    val currentCallState = client.calls[callId]?.callStateFlow?.value

                    // Allow ICE candidates when:
                    // 1. Call is NOT active (for initial invites/answers)
                    // 2. Call is in RENEGOTIATING state (for ICE restart)
                    if (currentCallState != CallState.ACTIVE ||
                        currentCallState == CallState.RENEGOTIATING
                    ) {
                        peerConnection?.addIceCandidate(it)
                        Logger.d(tag = "Observer", message = "ICE candidate added: $it")
                        onIceCandidateAdd?.invoke(it.serverUrl)
                        lastCandidateTime = System.currentTimeMillis()
                    } else {
                        Logger.d(
                            tag = "Observer",
                            message = "ICE candidate ignored - call is ACTIVE and not renegotiating"
                        )
                    }
                    peerConnectionObserver?.onIceCandidate(candidate)
                }
            }
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
     * Applies audio processing constraints (echo cancellation, noise suppression, auto gain control)
     * @param constraints Optional audio constraints to override constructor constraints
     * @see [AudioSource]
     * @see [AudioTrack]
     * @see [RtpTransceiver]
     */
    fun startLocalAudioCapture(constraints: AudioConstraints? = null) {
        Logger.d(tag = "Peer:Audio", message = "Attempting to start local audio capture...")

        // Use provided constraints, fall back to constructor constraints, or default to all enabled
        val effectiveConstraints = constraints ?: audioConstraints ?: AudioConstraints()
        Logger.d(
            tag = "Peer:Audio",
            message = "Audio constraints: echoCancellation=${effectiveConstraints.echoCancellation}, " +
                    "noiseSuppression=${effectiveConstraints.noiseSuppression}, " +
                    "autoGainControl=${effectiveConstraints.autoGainControl}"
        )

        val audioMediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                effectiveConstraints.echoCancellation.toString()
            ))
            mandatory.add(MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                effectiveConstraints.noiseSuppression.toString()
            ))
            mandatory.add(MediaConstraints.KeyValuePair(
                "googAutoGainControl",
                effectiveConstraints.autoGainControl.toString()
            ))
        }

        val audioSource: AudioSource = peerConnectionFactory.createAudioSource(audioMediaConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack(
            AUDIO_LOCAL_TRACK_ID,
            audioSource
        )

        if (localAudioTrack == null) {
            Logger.e(tag = "Peer:Audio", message = "Failed to create local audio track.")
            // Track getUserMedia failure
            client.debugDataCollector.onMicrophoneAccessError(callId, "Failed to create local audio track")
            return
        }

        localAudioTrack?.setEnabled(true)
        localAudioTrack?.setVolume(1.0)

        // Track getUserMedia success with track details
        val track = localAudioTrack
        if (track != null) {
            client.debugDataCollector.onGetUserMediaAttempt(
                callId,
                track.id() ?: "audio_local_track",
                track.state()?.name ?: "LIVE",
                track.enabled()
            )
        }
        Logger.d(
            tag = "Peer:Audio",
            message = "Local audio track created. ID: ${localAudioTrack?.id()}, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}"
        )

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
            Logger.w(
                tag = "Call",
                message = "Local audio track not initialized before creating offer."
            )
        }
        createOffer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                Logger.e(
                                    tag = "Call",
                                    message = "setLocalDescription onSetFailure $p0"
                                )
                            }

                            override fun onSetSuccess() {
                                Logger.d(tag = "Call", message = "setLocalDescription onSetSuccess")
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

        Logger.d(
            tag = "Answer",
            message = "Adjusting transceiver directions before createAnswer..."
        )
        this.transceivers.forEach { transceiver ->
            when (transceiver.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                        Logger.w(
                            tag = "Answer",
                            message = "Audio transceiver direction was ${transceiver.direction}. Setting to SEND_RECV."
                        )
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    } else {
                        Logger.d(
                            tag = "Answer",
                            message = "Audio transceiver direction already SEND_RECV."
                        )
                    }
                }

                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE) {
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                        Logger.d(
                            tag = "Answer",
                            message = "Setting video transceiver [Mid: ${transceiver.mid}] direction to INACTIVE"
                        )
                    } else {
                        Logger.d(
                            tag = "Answer",
                            message = "Video transceiver [Mid: ${transceiver.mid}] direction already INACTIVE."
                        )
                    }
                }

                else -> {
                    Logger.d(
                        tag = "Answer",
                        message = "Ignoring transceiver with unknown media type: ${transceiver.mediaType}"
                    )
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
                                Logger.e(
                                    tag = "Answer",
                                    message = "setLocalDescription onSetFailure $p0"
                                )
                            }

                            override fun onSetSuccess() {
                                Logger.d(
                                    tag = "Answer",
                                    message = "setLocalDescription onSetSuccess"
                                )
                                logAllTransceiverStates("After setLocalDescription success")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */
                            }

                            override fun onCreateFailure(p0: String?) { /* No-op */
                            }
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
            Logger.d(
                tag = "TransceiverState",
                message = "[$contextTag] Transceiver[$index]: Mid=${t.mid}, MediaType=${t.mediaType}, Direction=${t.direction}, CurrentDirection=${t.currentDirection}, Stopped=${t.isStopped}, SenderTrack=${
                    t.sender.track()?.id()
                }, ReceiverTrack=${t.receiver.track()?.id()}"
            )
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
        Logger.d(
            tag = "SDP",
            message = "[Remote Offer/Answer SDP Received]:\n${sessionDescription.description}"
        )
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0)
                    Logger.e(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description Failed: $p0"
                    )
                }

                override fun onSetSuccess() {
                    Logger.d(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description Success"
                    )
                    logAllTransceiverStates("After setRemoteDescription success")
                }

                override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */
                }

                override fun onCreateFailure(p0: String?) {
                    Logger.e(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description reported onCreateFailure: $p0"
                    )
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
     * Sends an ICE candidate via signaling for trickle ICE
     * @param candidate the [IceCandidate] to send
     */
    private fun sendIceCandidate(candidate: IceCandidate) {
        val call = client.calls[callId]
        call?.let {
            // Clean the candidate string to remove WebRTC-specific extensions
            val cleanedCandidateString = SdpUtils.cleanCandidateString(candidate.sdp)

            val candidateMessage = SendingMessageBody(
                id = UUID.randomUUID().toString(),
                method = SocketMethod.CANDIDATE.methodName,
                params = CandidateParams(
                    sessid = it.sessionId,
                    candidate = cleanedCandidateString,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    dialogParams = CandidateDialogParams(
                        callId = callId
                    )
                )
            )

            Logger.d(
                tag = "CandidateSend",
                message = "Sending cleaned candidate: $cleanedCandidateString"
            )
            client.socket.send(candidateMessage)
        }
    }

    /**
     * Sends end-of-candidates signal for trickle ICE
     */
    private fun sendEndOfCandidates() {
        // Prevent sending duplicate end-of-candidates signals
        if (endOfCandidatesSent) {
            Logger.d(tag = "EndOfCandidates", message = "Already sent, skipping duplicate")
            return
        }

        val call = client.calls[callId]
        call?.let {
            val endOfCandidatesMessage = SendingMessageBody(
                id = UUID.randomUUID().toString(),
                method = SocketMethod.END_OF_CANDIDATES.methodName,
                params = EndOfCandidatesParams(
                    sessid = it.sessionId,
                    dialogParams = CandidateDialogParams(
                        callId = callId
                    )
                )
            )
            client.socket.send(endOfCandidatesMessage)
            endOfCandidatesSent = true

            // Stop the timer since we've sent the signal
            stopEndOfCandidatesTimer()

            Logger.d(tag = "EndOfCandidates", message = "Signal sent successfully")
        }
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
                    Logger.d(
                        tag = "NegotiationTimer",
                        message = "Negotiation timeout reached - Invoking onNegotiationComplete"
                    )
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
     * Starts ICE renegotiation when ICE connection fails
     * Creates a new offer with ICE restart enabled
     */
    private fun startIceRenegotiation() {
        Logger.d(tag = "ICE_RENEGOTIATION", message = "Starting ICE renegotiation process")

        // Set call state to RENEGOTIATING to allow ICE candidate processing
        client.calls[callId]?.updateCallState(CallState.RENEGOTIATING)

        // turn off IceTrickle, as it is not required for renegotiation:
        client.setUseTrickleIce(false)

        val iceRestartConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let { sdp ->
                    Logger.d(
                        tag = "ICE_RENEGOTIATION",
                        message = "ICE restart offer created successfully"
                    )
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Logger.d(
                                tag = "ICE_RENEGOTIATION",
                                message = "Local description set for ICE restart"
                            )

                            // Start negotiation timer to wait for ICE candidates before sending updateMedia
                            // This is the same mechanism used for invite/accept flow
                            setOnNegotiationComplete {
                                Logger.d(
                                    tag = "ICE_RENEGOTIATION",
                                    message = "ICE negotiation complete for renegotiation, sending updateMedia message"
                                )
                                // Get the current local description which should now include candidates
                                val currentSdp = peerConnection?.localDescription?.description
                                if (currentSdp != null) {
                                    sendUpdateMediaMessage(currentSdp)
                                } else {
                                    Logger.e(
                                        tag = "ICE_RENEGOTIATION",
                                        message = "Failed to get local description after negotiation"
                                    )
                                    client.calls[callId]?.updateCallState(CallState.ACTIVE)
                                }
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            Logger.e(
                                tag = "ICE_RENEGOTIATION",
                                message = "Failed to set local description for ICE restart: $error"
                            )
                            // Reset call state back to ACTIVE on failure
                            client.calls[callId]?.updateCallState(CallState.ACTIVE)
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {
                            Logger.i(
                                tag = "ICE_RENEGOTIATION",
                                message = "onCreateSuccess called in setLocalDescription"
                            )
                        }

                        override fun onCreateFailure(p0: String?) {
                            Logger.e(
                                tag = "ICE_RENEGOTIATION",
                                message = "onCreateFailure called in setLocalDescription: $p0"
                            )
                            // Reset call state back to ACTIVE on failure
                            client.calls[callId]?.updateCallState(CallState.ACTIVE)
                        }
                    }, sdp)
                }
            }

            override fun onCreateFailure(error: String?) {
                Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "Failed to create ICE restart offer: $error"
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
            }

            override fun onSetSuccess() {
                Logger.i(
                    tag = "ICE_RENEGOTIATION",
                    message = "onSetSuccess called in createOffer"
                )
            }

            override fun onSetFailure(p0: String?) {
                Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "onSetFailure called in createOffer: $p0"
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
            }
        }, iceRestartConstraints)
    }

    /**
     * Sends the updateMedia modify message with the new SDP for ICE renegotiation
     */
    private fun sendUpdateMediaMessage(sdp: String) {
        Logger.d(tag = "ICE_RENEGOTIATION", message = "Sending updateMedia message")

        // Reset call state back to ACTIVE after sending the updateMedia message
        client.calls[callId]?.updateCallState(CallState.ACTIVE)

        client.sendUpdateMediaMessage(callId, sdp)
    }

    /**
     * Handles ICE connection state transitions for automatic recovery
     *
     * @param previousState Previous ICE connection state
     * @param newState New ICE connection state
     */
    private fun handleIceConnectionStateTransition(
        previousState: PeerConnection.IceConnectionState?,
        newState: PeerConnection.IceConnectionState?
    ) {
        if (previousState == null || newState == null) {
            Logger.d(
                tag = "IceStateTransition",
                message = "ICE state transition: null -> $newState"
            )
            return
        }

        Logger.d(
            tag = "IceStateTransition",
            message = "ICE state transition: $previousState -> $newState"
        )

        // Case 1: disconnected -> failed: Attempt ICE restart/renegotiation
        if (previousState == PeerConnection.IceConnectionState.DISCONNECTED &&
            newState == PeerConnection.IceConnectionState.FAILED
        ) {
            Logger.w(
                tag = "IceStateTransition",
                message = "ICE connection failed after disconnect - attempting ICE restart"
            )

            // Trigger ICE restart to recover from failed state with delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    startIceRenegotiation()
                }
            }, ICE_RESTART_DELAY_MS)
        }

        // Case 2: connected -> disconnected: Reset audio buffers
        if (previousState == PeerConnection.IceConnectionState.CONNECTED &&
            newState == PeerConnection.IceConnectionState.DISCONNECTED
        ) {
            Logger.w(
                tag = "IceStateTransition",
                message = "ICE connection disconnected - resetting audio buffers"
            )

            // Reset audio device module to clear accumulated buffers with delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    resetAudioDeviceModule()
                }
            }, AUDIO_BUFFER_RESET_DELAY_MS)
        }

        // Case 3: disconnected -> connected: Reset audio buffers
        if (previousState == PeerConnection.IceConnectionState.DISCONNECTED &&
            newState == PeerConnection.IceConnectionState.CONNECTED
        ) {
            Logger.i(
                tag = "IceStateTransition",
                message = "ICE connection restored - resetting audio buffers"
            )

            // Reset audio device module to clear accumulated buffers with delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    resetAudioDeviceModule()
                }
            }, AUDIO_BUFFER_RESET_DELAY_MS)
        }

        // Handle connected/completed states for logging
        if (newState == PeerConnection.IceConnectionState.CONNECTED ||
            newState == PeerConnection.IceConnectionState.COMPLETED
        ) {
            logAudioTrackAndTransceiverState("onIceConnectionChange ($newState)")
        }
    }

    /**
     * Resets the audio device module to clear accumulated buffers
     * Similar to iOS implementation
     */
    private fun resetAudioDeviceModule() {
        Logger.d(tag = "AudioReset", message = "Resetting audio device module")

        // Stop and restart local audio capture to clear buffers
        localAudioTrack?.setEnabled(false)
        Timer().schedule(object : TimerTask() {
            override fun run() {
                localAudioTrack?.setEnabled(true)
                Logger.d(tag = "AudioReset", message = "Audio device module reset completed")
            }
        }, AUDIO_RE_ENABLE_DELAY_MS)
    }

    /**
     * Handles the updateMedia response containing the new remote SDP
     * This completes the ICE renegotiation process
     */
    fun handleUpdateMediaResponse(remoteSdp: String) {
        Logger.d(
            tag = "ICE_RENEGOTIATION",
            message = "Handling updateMedia response with remote SDP"
        )

        val remoteSessionDescription = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Logger.d(
                    tag = "ICE_RENEGOTIATION",
                    message = "ICE renegotiation completed successfully"
                )
                // Ensure call state is reset to ACTIVE on successful completion
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
            }

            override fun onSetFailure(error: String?) {
                Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "Failed to set remote description for ICE renegotiation: $error"
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
                Logger.i(
                    tag = "ICE_RENEGOTIATION",
                    message = "onCreateSuccess called in setRemoteDescription"
                )
            }

            override fun onCreateFailure(p0: String?) {
                Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "onCreateFailure called in setRemoteDescription: $p0"
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
            }
        }, remoteSessionDescription)
    }

    /**
     * Flushes all queued ICE candidates after ANSWER is sent.
     * This is called when the answering side sends the ANSWER message.
     */
    internal fun flushQueuedCandidatesAfterAnswer() {
        if (!client.getUseTrickleIce() || !isAnswering) {
            Logger.d(
                tag = "CandidateFlush",
                message = "Not flushing - trickleIce=${client.getUseTrickleIce()}, isAnswering=$isAnswering"
            )
            return
        }

        Logger.d(
            tag = "CandidateFlush",
            message = "Flushing ${queuedCandidates.size} queued candidates after ANSWER sent"
        )

        val hadCandidates = queuedCandidates.isNotEmpty()

        // Send all queued candidates
        queuedCandidates.forEach { candidate ->
            sendIceCandidate(candidate)
            Logger.d(tag = "CandidateFlush", message = "Flushed queued candidate: $candidate")
        }

        // Clear the queue and mark answer as sent
        queuedCandidates.clear()
        answerSent = true

        // Start the end-of-candidates timer after flushing candidates (if there were any)
        if (hadCandidates) {
            startEndOfCandidatesTimer()
        }

        Logger.d(tag = "CandidateFlush", message = "Candidate flush completed, answerSent = true")
    }

    /**
     * Starts the end-of-candidates timer that sends end-of-candidates signal
     * after a period of inactivity in ICE candidate discovery
     */
    private fun startEndOfCandidatesTimer() {
        // Only start timer if trickle ICE is enabled and end-of-candidates not already sent
        if (!client.getUseTrickleIce()) {
            Logger.d(
                tag = "EndOfCandidatesTimer",
                message = "Timer not started - Trickle ICE disabled"
            )
            return
        }
        if (endOfCandidatesSent) {
            Logger.d(
                tag = "EndOfCandidatesTimer",
                message = "Timer not started - end-of-candidates already sent"
            )
            return
        }

        // Cancel any existing timer
        endOfCandidatesTimer?.cancel()
        endOfCandidatesTimer?.purge()

        endOfCandidatesTimer = Timer()
        endOfCandidatesTimer?.schedule(
            timerTask {
                Logger.d(
                    tag = "EndOfCandidatesTimer",
                    message = "Timer triggered after ${END_OF_CANDIDATES_TIMEOUT}ms of inactivity"
                )

                // Send end-of-candidates if not already sent
                if (!endOfCandidatesSent && client.getUseTrickleIce()) {
                    Logger.d(
                        tag = "EndOfCandidatesTimer",
                        message = "Sending end-of-candidates via timer"
                    )
                    sendEndOfCandidates()
                }

                stopEndOfCandidatesTimer()
            },
            END_OF_CANDIDATES_TIMEOUT
        )

        Logger.d(tag = "EndOfCandidatesTimer", message = "Timer started/restarted")
    }

    /**
     * Stops and cleans up the end-of-candidates timer
     */
    private fun stopEndOfCandidatesTimer() {
        endOfCandidatesTimer?.cancel()
        endOfCandidatesTimer?.purge()
        endOfCandidatesTimer = null
        Logger.d(tag = "EndOfCandidatesTimer", message = "Timer stopped")
    }

    /**
     * Applies audio codec preferences to the peer connection's audio transceiver.
     * This method must be called before creating an offer or answer to ensure the
     * preferred codecs are negotiated in the correct order.
     *
     * @param preferredCodecs List of preferred audio codecs in order of preference
     */
    fun applyAudioCodecPreferences(preferredCodecs: List<AudioCodec>?) {
        if (preferredCodecs.isNullOrEmpty()) {
            Logger.d(tag = "CodecPreferences", message = "No codec preferences provided, using defaults")
            return
        }

        try {
            // Find the audio transceiver
            val audioTransceiver = CodecUtils.findAudioTransceiver(peerConnection?.transceivers) ?: run {
                Logger.w(tag = "CodecPreferences", message = "No audio transceiver found, cannot apply codec preferences")
                return
            }

            // Convert AudioCodec list directly to CodecCapability list
            val codecCapabilities = CodecUtils.convertAudioCodecsToCapabilities(preferredCodecs)
            if (codecCapabilities.isEmpty()) {
                Logger.w(tag = "CodecPreferences", message = "No valid codec capabilities created, using defaults")
            } else {
                // Apply codec preferences to transceiver
                audioTransceiver.setCodecPreferences(codecCapabilities)
                Logger.d(tag = "CodecPreferences", message = "Successfully applied codec preferences. Order: ${codecCapabilities.map { it.mimeType }}")
            }
        } catch (e: Exception) {
            Logger.e(tag = "CodecPreferences", message = "Error applying codec preferences: ${e.message}")
        }
    }

    /**
     * Gets the list of audio codecs that are currently available from the WebRTC peer connection.
     * This reflects the actual codecs that can be used for negotiation.
     *
     * @return List of AudioCodec objects representing available codecs, or empty list if unavailable
     */
    fun getAvailableAudioCodecs(): List<AudioCodec> {
        val audioTransceiver = CodecUtils.findAudioTransceiver(peerConnection?.transceivers)
            ?: run {
                Logger.w(tag = "CodecQuery", message = "No audio transceiver found")
                return emptyList()
            }

        return CodecUtils.getAvailableAudioCodecs(audioTransceiver)
    }

    /**
     * Cleans up resources when the peer is no longer needed
     */
    fun release() {
        Logger.d(message = "Releasing Peer resources...")
        stopNegotiationTimer()
        stopEndOfCandidatesTimer()
        // Clear queued candidates and reset flags
        queuedCandidates.clear()
        answerSent = false
        endOfCandidatesSent = false
        if (peerConnection != null) {
            disconnect()
        }
    }

    /**
     * Forces ICE renegotiation for testing purposes.
     * This method directly calls startIceRenegotiation() to test the renegotiation logic.
     */
    fun forceIceRenegotiationForTesting() {
        Logger.w(tag = "IceRenegotiationTest", message = "Forcing ICE renegotiation for testing")
        startIceRenegotiation()
    }

    /**
     * Extracts the candidate type from an ICE candidate SDP string.
     * Example SDP: "candidate:0 1 UDP 2122252543 192.168.1.1 54321 typ host"
     *
     * @param sdp The ICE candidate SDP string
     * @return The candidate type (host, srflx, prflx, relay) or "unknown"
     */
    private fun extractCandidateType(sdp: String): String {
        return try {
            val typIndex = sdp.indexOf(TYP_PREFIX)
            if (typIndex != -1) {
                val typeStart = typIndex + TYP_PREFIX.length
                val typeEnd = sdp.indexOf(' ', typeStart).takeIf { it != -1 } ?: sdp.length
                sdp.substring(typeStart, typeEnd)
            } else {
                UNKNOWN_VALUE
            }
        } catch (e: Exception) {
            Logger.d(tag = "Peer", message = "Failed to extract candidate type: ${e.message}")
            UNKNOWN_VALUE
        }
    }

    /**
     * Extracts the protocol from an ICE candidate SDP string.
     * Example SDP: "candidate:0 1 UDP 2122252543 192.168.1.1 54321 typ host"
     *
     * @param sdp The ICE candidate SDP string
     * @return The protocol (udp, tcp) or "unknown"
     */
    private fun extractProtocol(sdp: String): String {
        return try {
            val parts = sdp.split(" ")
            if (parts.size >= PROTOCOL_INDEX + 1) {
                parts[PROTOCOL_INDEX].lowercase()
            } else {
                UNKNOWN_VALUE
            }
        } catch (e: Exception) {
            Logger.d(tag = "Peer", message = "Failed to extract protocol: ${e.message}")
            UNKNOWN_VALUE
        }
    }

    /**
     * Initializes the Peer with the provided context and builds the PeerConnection.
     * Uses the shared PeerConnectionFactory from the companion object.
     */
    init {
        // Ensure WebRTC is initialized using companion's method
        initPeerConnectionFactory(context)
        peerConnection = buildPeerConnection()
        // Reset flags when a new Peer is created
        firstCandidateReceived = false
        answerSent = false
        endOfCandidatesSent = false
        queuedCandidates.clear()
    }
}
