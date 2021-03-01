package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import org.webrtc.IceCandidate

interface TxSocketListener {
    fun onLoginSuccessful(jsonObject: JsonObject)
    fun onByeReceived()
    fun onConnectionEstablished()
    fun onOfferReceived(jsonObject: JsonObject)
    fun onAnswerReceived(jsonObject: JsonObject)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}