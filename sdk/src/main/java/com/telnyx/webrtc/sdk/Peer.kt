package com.telnyx.webrtc.sdk

import android.content.Context
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config.Companion.DEFAULT_STUN
import com.telnyx.webrtc.sdk.Config.Companion.DEFAULT_TURN
import com.telnyx.webrtc.sdk.Config.Companion.TEST_USERNAME
import com.telnyx.webrtc.sdk.Config.Companion.TEST_PASSWORD
import org.webrtc.*
import timber.log.Timber
import java.util.*

class Peer(
        val client: TelnyxClient,
        context: Context,
        observer: PeerConnection.Observer
) {

    companion object {
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
    }

    private val rootEglBase: EglBase = EglBase.create()

    init {
        initPeerConnectionFactory(context)
    }

    private val iceServer = getIceServers()

    private fun getIceServers(): List<PeerConnection.IceServer> {
        val iceServers: MutableList<PeerConnection.IceServer> = ArrayList()
        iceServers.add(
                PeerConnection.IceServer.builder(DEFAULT_STUN).setUsername(TEST_USERNAME).setPassword(
                        TEST_PASSWORD
                ).createIceServer()
        )
        iceServers.add(
                PeerConnection.IceServer.builder(DEFAULT_TURN).setUsername(TEST_USERNAME).setPassword(
                        TEST_PASSWORD
                ).createIceServer()
        )
        return iceServers
    }

    private val peerConnectionFactory by lazy { buildPeerConnectionFactory() }
    private val peerConnection by lazy { buildPeerConnection(observer) }

    private fun initPeerConnectionFactory(context: Context) {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                //.setFieldTrials("WebRTC-IntelVP8/Enabled/")
                .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
    }

    private fun buildPeerConnectionFactory(): PeerConnectionFactory {
        return PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = true
                })
                .createPeerConnectionFactory()

    }

    private fun buildPeerConnection(observer: PeerConnection.Observer) = peerConnectionFactory.createPeerConnection(
            iceServer,
            observer
    )

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

    fun createOfferForSdp(sdpObserver: SdpObserver) = peerConnection?.call(sdpObserver)

    fun answer(sdpObserver: SdpObserver) = peerConnection?.answer(sdpObserver)

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

    fun addIceCandidate(iceCandidate: IceCandidate?) {
        peerConnection?.addIceCandidate(iceCandidate)
    }

    fun getLocalDescription(): SessionDescription? {
        return peerConnection?.localDescription
    }

    fun disconnect() {
        peerConnection?.close()
        peerConnection?.dispose()
    }

}
