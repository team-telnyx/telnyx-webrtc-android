package com.telnyx.webrtc.sdk.model

enum class CauseCode(var code: Int) {
    USER_BUSY(1),
    NORMAL_CLEARING(2),
    INVALID_GATEWAY(3),
    ORIGINATOR_CANCEL(4)
}