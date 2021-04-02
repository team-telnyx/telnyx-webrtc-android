package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import org.webrtc.IceCandidate
import java.util.*

interface  TxSocketCallListener {
    fun onByeReceived(callId: UUID)
    fun onAnswerReceived(jsonObject: JsonObject)
    fun onMediaReceived(jsonObject: JsonObject)
    fun onOfferReceived(jsonObject: JsonObject)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}