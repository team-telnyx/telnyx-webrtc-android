package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.sdk.model.CallState

/** Per-call identity, retained by the Call and its original Peer across delayed callbacks. */
internal data class V2CaptureOptions(val forceRelay: Boolean, val prefetch: Boolean,
    val customIce: Boolean, val iceServerCount: Int)

internal class V2CaptureToken

/** Native ingress only; the optional KMP adapter owns all reporting state and serialization. */
internal interface CallReportV2Ingress {
    fun started(token: V2CaptureToken, callId: String, inbound: Boolean, reattach: Boolean, trickle: Boolean, options: V2CaptureOptions)
    fun state(token: V2CaptureToken, state: CallState)
    fun stats(token: V2CaptureToken, report: RTCStatsReport)
    fun loginType(type: String)
    fun signaling(generation: Long, voiceSdkId: String?, sessionId: String?)
    fun peerClosing(token: V2CaptureToken, report: RTCStatsReport?)
}
