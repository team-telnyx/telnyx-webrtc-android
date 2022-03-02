/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import timber.log.Timber

/**
 * The application SDP Observer that can be used to observe SDP related events
 * like successfully setting the local SDP or any possible failures
 *
 * @see SdpObserver
 */
internal open class AppSdpObserver : SdpObserver {
    override fun onSetFailure(p0: String?) {
        Timber.tag("AppSdpObserver").d("onSetFailure [%s]", "$p0")
    }

    override fun onSetSuccess() {
        Timber.tag("AppSdpObserver").d("onSetSuccess")
    }

    override fun onCreateSuccess(sessionDescription: SessionDescription?) {
        Timber.tag("AppSdpObserver").d("onCreateSuccess [%s]", "$sessionDescription")
    }

    override fun onCreateFailure(p0: String?) {
        Timber.tag("AppSdpObserver").d("onCreateFailure [%s]", "$p0")
    }
}
