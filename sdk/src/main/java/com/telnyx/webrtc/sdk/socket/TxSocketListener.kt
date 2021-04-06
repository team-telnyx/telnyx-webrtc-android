package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject

interface TxSocketListener {
    fun onLoginSuccessful(jsonObject: JsonObject)
    fun onConnectionEstablished()
  //  fun onOfferReceived(jsonObject: JsonObject)
    fun onErrorReceived(jsonObject: JsonObject)
}