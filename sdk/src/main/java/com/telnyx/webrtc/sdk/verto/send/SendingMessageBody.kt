package com.telnyx.webrtc.sdk.verto.send

import com.telnyx.webrtc.sdk.verto.send.ParamRequest

class SendingMessageBody(val id: String, val method: String, val params: ParamRequest, val jsonrpc: String = "2.0")
