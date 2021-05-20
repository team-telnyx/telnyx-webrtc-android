/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import com.telnyx.webrtc.sdk.Config.Companion.DEFAULT_STUN
import com.telnyx.webrtc.sdk.Config.Companion.DEFAULT_TURN
import com.telnyx.webrtc.sdk.Config.Companion.PASSWORD
import com.telnyx.webrtc.sdk.Config.Companion.USERNAME
import com.telnyx.webrtc.sdk.socket.TxSocket
import org.webrtc.*
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
    observer: PeerConnection.Observer
) {

    internal var audioSender: RtpSender? = null

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
    }

    private val rootEglBase: EglBase = EglBase.create()

    init {
        initPeerConnectionFactory(context)
    }

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
        iceServers.add(
            PeerConnection.IceServer.builder(DEFAULT_STUN).setUsername(USERNAME).setPassword(
                PASSWORD
            ).createIceServer()
        )
        iceServers.add(
            PeerConnection.IceServer.builder(DEFAULT_TURN).setUsername(USERNAME).setPassword(
                PASSWORD
            ).createIceServer()
        )
        return iceServers
    }

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val peerConnection by lazy { buildPeerConnection(observer) }

    /**
     * Initiates our peer connection factory with the specified options
     * @param context the context
     */
    private fun initPeerConnectionFactory(context: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            //.setFieldTrials("WebRTC-IntelVP8/Enabled/")
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
            .setOptions(PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = true
            })
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
        peerConnection?.addStream(localStream)
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
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        createOffer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                setLocalDescription(object : SdpObserver {
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
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }
        }, constraints)
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
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
        }

        createAnswer(object : SdpObserver by sdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {
                setLocalDescription(object : SdpObserver {
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
                }, desc)
                sdpObserver.onCreateSuccess(desc)
            }
        }, constraints)
    }

    fun getRTPSenders(): MutableList<RtpSender>? {
            return peerConnection?.senders
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
        peerConnection?.setRemoteDescription(object : SdpObserver {
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
        }, sessionDescription)
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
    }
}
