package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import org.webrtc.IceCandidate
import java.util.*

interface TxSocketListener {
    fun onLoginSuccessful(jsonObject: JsonObject)
    fun onConnectionEstablished()
    fun onErrorReceived(jsonObject: JsonObject)
    fun onByeReceived(callId: UUID)
    fun onAnswerReceived(jsonObject: JsonObject)
    fun onMediaReceived(jsonObject: JsonObject)
    fun onOfferReceived(jsonObject: JsonObject)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}