package org.telnyx.webrtc.xmlapp

import androidx.test.espresso.IdlingResource

class ElapsedTimeIdlingResource(private val waitingTime: Long) : IdlingResource {
    private val startTime = System.currentTimeMillis()
    private var resourceCallback: IdlingResource.ResourceCallback? = null

    override fun getName() = "ElapsedTimeIdlingResource"

    override fun isIdleNow(): Boolean {
        val idle = System.currentTimeMillis() >= startTime + waitingTime
        if (idle) resourceCallback?.onTransitionToIdle()
        return idle
    }

    override fun registerIdleTransitionCallback(callback: IdlingResource.ResourceCallback?) {
        resourceCallback = callback
    }
}
