package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject

interface TxSocketListener {
    fun onLoginSuccessful(jsonObject: JsonObject)
    fun onConnectionEstablished()
}