package com.telnyx.webrtc.sdk

interface PlatformSocketListener {
    fun onOpen()
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
    fun onMessage(msg: String)
}