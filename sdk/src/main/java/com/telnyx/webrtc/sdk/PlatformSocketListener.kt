package com.telnyx.webrtc.library.socket

interface PlatformSocketListener {
    fun onOpen()
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
    fun onMessage(msg: String)
}