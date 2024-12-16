/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.stats.StatsData
import com.telnyx.webrtc.sdk.stats.WebRTCStatsEvent
import com.telnyx.webrtc.sdk.stats.WebRTCReporter
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.RtpReceiver
import timber.log.Timber

/**
 * Class that represents and implements the WEBRTC events including ICE, Track, Stream an Signal change events.
 */
internal open class PeerConnectionObserver(private val statsManager: WebRTCReporter) : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        Timber.tag("PeerObserver").d("onSignalingChange [%s]", "$p0")
        statsManager.onStatsDataEvent(StatsData.PeerEvent(WebRTCStatsEvent.SIGNALING_CHANGE,"$p0"))
    }

    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        Timber.tag("PeerObserver").d("onIceConnectionChange [%s]", "$p0")
    }

    override fun onIceConnectionReceivingChange(p0: Boolean) {
        Timber.tag("PeerObserver").d("onIceConnectionReceivingChange [%s]", "$p0")
    }

    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        Timber.tag("PeerObserver").d("onIceGatheringChange [%s]", "$p0")
        statsManager.onStatsDataEvent(StatsData.PeerEvent(WebRTCStatsEvent.ICE_GATHER_CHANGE,p0))
    }

    override fun onIceCandidate(p0: IceCandidate?) {
        Timber.tag("PeerObserver").d("onIceCandidate Generated [%s]", "$p0")
        statsManager.onStatsDataEvent(StatsData.PeerEvent(WebRTCStatsEvent.ON_ICE_CANDIDATE,p0))
    }

    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        Timber.tag("PeerObserver").d("onIceCandidatesRemoved [%s]", "$p0")
    }

    override fun onAddStream(p0: MediaStream?) {
        Timber.tag("PeerObserver").d("onAddStream [%s]", "$p0")
    }

    override fun onRemoveStream(p0: MediaStream?) {
        Timber.tag("PeerObserver").d("onRemoveStream [%s]", "$p0")
    }

    override fun onDataChannel(p0: DataChannel?) {
        Timber.tag("PeerObserver").d("onDataChannel [%s]", "$p0")
    }

    override fun onRenegotiationNeeded() {
        Timber.tag("PeerObserver").d("onRenegotiationNeeded")
        statsManager.onStatsDataEvent(StatsData.PeerEvent(WebRTCStatsEvent.ON_RENEGOTIATION_NEEDED, null))
    }

    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        Timber.tag("PeerObserver").d("onAddTrack [%s] [%s]", "$p0", "$p1")
        statsManager.onStatsDataEvent(StatsData.PeerEvent(WebRTCStatsEvent.ON_ADD_TRACK,p1))
    }
}
