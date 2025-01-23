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
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.socket.TxSocket
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

    }

    private val rootEglBase: EglBase = EglBase.create()
    private var isDebugStats = false

    internal var debugStatsId = UUID.randomUUID()


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
    internal var peerConnection: PeerConnection? = null

    internal var peerConnectionObserver: PeerConnectionObserver? = null

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            peerConnectionObserver?.onSignalingChange(p0)
        }

        override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
            peerConnectionObserver?.onIceConnectionChange(p0)
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            peerConnectionObserver?.onIceConnectionReceivingChange(p0)
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            peerConnectionObserver?.onIceGatheringChange(p0)
        }

        override fun onIceCandidate(p0: IceCandidate?) {
            Timber.d("Event-IceCandidate Generated")
            if (client.calls[callId]?.getCallState()?.value != CallState.ACTIVE) {
                addIceCandidate(p0)
                Timber.d("Event-IceCandidate Added")
            }

            Timber.d("Event-IceCandidate Generated ")
            p0?.let {
                if (!it.serverUrl.isNullOrEmpty()) { // Host has empty serverUrl
                    onIceCandidateAdd?.invoke(it.serverUrl)
                    //iceCandidateList.add(it.serverUrl)
                }
            }
            if (client.calls[callId]?.getCallState()?.value != CallState.ACTIVE) {
                peerConnection?.let { connection ->
                    connection.addIceCandidate(p0)
                    Timber.d("Event-IceCandidate Added ${p0}")
                }
            }

            peerConnectionObserver?.onIceCandidate(p0)
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            peerConnectionObserver?.onIceCandidatesRemoved(p0)
        }

        override fun onAddStream(p0: MediaStream?) {
            peerConnectionObserver?.onAddStream(p0)
        }

        override fun onRemoveStream(p0: MediaStream?) {
            peerConnectionObserver?.onRemoveStream(p0)
        }

        override fun onDataChannel(p0: DataChannel?) {
            peerConnectionObserver?.onDataChannel(p0)
        }

        override fun onRenegotiationNeeded() {
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
    private fun buildPeerConnection() =
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
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
    }

    fun release() {
        if (peerConnection != null) {
            disconnect()
            peerConnectionFactory.dispose()
        }
        if (isDebugStats){
            //stopTimer()
        }
    }

    init {
        initPeerConnectionFactory(context)
        peerConnection = buildPeerConnection()
    }
}
