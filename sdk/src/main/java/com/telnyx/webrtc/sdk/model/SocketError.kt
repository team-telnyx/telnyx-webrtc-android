package com.telnyx.webrtc.sdk.model

enum class SocketError(var errorCode: Int) {
    TOKEN_ERROR(-32000),
    CREDENTIAL_ERROR(-32001)
}

