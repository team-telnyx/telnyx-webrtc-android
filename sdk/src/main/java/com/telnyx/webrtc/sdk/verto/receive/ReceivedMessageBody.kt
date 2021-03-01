package com.telnyx.webrtc.sdk.verto.receive

import com.telnyx.webrtc.sdk.verto.receive.ReceivedResult

data class ReceivedMessageBody(val method:String,val result : ReceivedResult?){
}