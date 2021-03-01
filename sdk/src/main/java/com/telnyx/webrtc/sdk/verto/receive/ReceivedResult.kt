package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName

sealed class ReceivedResult

data class LoginResponse(val sessid: String) : ReceivedResult()

data class AnswerResponse(
    @SerializedName("callID")
    val callId: String,
    val sdp: String
) : ReceivedResult()

data class InviteResponse(
    @SerializedName("callID")
    val callId: String,
    val sdp: String,
    @SerializedName("caller_id_name")
    val callerIdName: String,
    @SerializedName("caller_id_number")
    val callerIdNumber: String,
    @SerializedName("telnyx_session_id")
    val sessionId: String
) : ReceivedResult()