package com.telnyx.webrtc.sdk

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription

internal open class AppSdpObserver : SdpObserver {
    override fun onSetFailure(p0: String?) {
    }

    override fun onSetSuccess() {
    }

    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
    }

    override fun onCreateFailure(p0: String?) {
    }
}