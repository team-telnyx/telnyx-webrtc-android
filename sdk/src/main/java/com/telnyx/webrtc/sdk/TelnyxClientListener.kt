package com.telnyx.webrtc.sdk

interface TelnyxClientListener {
    fun onSocketConnected()
    fun onSocketDisconnected()
    fun onClientReady()
    fun onSessionUpdated(sessionId: String)
    fun onClientError(error: String)
}