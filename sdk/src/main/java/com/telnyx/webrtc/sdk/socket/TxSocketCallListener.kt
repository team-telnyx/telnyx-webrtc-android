package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import org.webrtc.IceCandidate

interface  TxSocketCallListener {
    fun onByeReceived()
    fun onAnswerReceived(jsonObject: JsonObject)
    fun onMediaReceived(jsonObject: JsonObject)
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}