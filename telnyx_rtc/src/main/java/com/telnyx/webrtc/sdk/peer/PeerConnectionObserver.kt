/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.stats.StatsData
import com.telnyx.webrtc.sdk.stats.WebRTCStatsEvent
import com.telnyx.webrtc.sdk.stats.WebRTCReporter
import com.telnyx.webrtc.lib.DataChannel
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.MediaStream
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.RtpReceiver
import timber.log.Timber

/**
 * Class that represents and implements the WEBRTC events including ICE, Track, Stream an Signal change events.
 */
open class PeerConnectionObserver : PeerConnection.Observer {
    /**
     * Called when the signaling state of the PeerConnection changes.
     *
     * @param p0 The new signaling state
     */
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
        Timber.tag("PeerObserver").d("onSignalingChange [%s]", "$p0")
    }

    /**
     * Called when the ICE connection state changes.
     *
     * @param p0 The new ICE connection state
     */
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
        Timber.tag("PeerObserver").d("onIceConnectionChange [%s]", "$p0")
    }

    /**
     * Called when the ICE connection receiving state changes.
     *
     * @param p0 True if ICE connection is receiving, false otherwise
     */
    override fun onIceConnectionReceivingChange(p0: Boolean) {
        Timber.tag("PeerObserver").d("onIceConnectionReceivingChange [%s]", "$p0")
    }

    /**
     * Called when the ICE gathering state changes.
     *
     * @param p0 The new ICE gathering state
     */
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
        Timber.tag("PeerObserver").d("onIceGatheringChange [%s]", "$p0")
    }

    /**
     * Called when a new ICE candidate has been generated.
     *
     * @param p0 The new ICE candidate
     */
    override fun onIceCandidate(p0: IceCandidate?) {
        Timber.tag("PeerObserver").d("onIceCandidate Generated [%s]", "$p0")
    }

    /**
     * Called when ICE candidates have been removed.
     *
     * @param p0 Array of removed ICE candidates
     */
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
        Timber.tag("PeerObserver").d("onIceCandidatesRemoved [%s]", "$p0")
    }

    /**
     * Called when a new media stream has been added to the connection.
     *
     * @param p0 The added media stream
     */
    override fun onAddStream(p0: MediaStream?) {
        Timber.tag("PeerObserver").d("onAddStream [%s]", "$p0")
    }

    /**
     * Called when a media stream has been removed from the connection.
     *
     * @param p0 The removed media stream
     */
    override fun onRemoveStream(p0: MediaStream?) {
        Timber.tag("PeerObserver").d("onRemoveStream [%s]", "$p0")
    }

    /**
     * Called when a new data channel has been created on the connection.
     *
     * @param p0 The new data channel
     */
    override fun onDataChannel(p0: DataChannel?) {
        Timber.tag("PeerObserver").d("onDataChannel [%s]", "$p0")
    }

    /**
     * Called when the connection needs to be renegotiated.
     * This can happen when media streams or data channels are added or removed.
     */
    override fun onRenegotiationNeeded() {
        Timber.tag("PeerObserver").d("onRenegotiationNeeded")
    }

    /**
     * Called when a new track has been added to the connection.
     *
     * @param p0 The RTP receiver for the new track
     * @param p1 Array of media streams that the track was added to
     */
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {
        Timber.tag("PeerObserver").d("onAddTrack [%s] [%s]", "$p0", "$p1")
    }
}
