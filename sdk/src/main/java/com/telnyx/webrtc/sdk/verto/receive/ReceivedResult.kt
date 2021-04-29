/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName
import java.util.*

sealed class ReceivedResult

data class LoginResponse(val sessid: String) : ReceivedResult()

data class AnswerResponse(
    @SerializedName("callID")
    val callId: UUID,
    val sdp: String
) : ReceivedResult()

data class InviteResponse(
    @SerializedName("callID")
    val callId: UUID,
    val sdp: String,
    @SerializedName("caller_id_name")
    val callerIdName: String,
    @SerializedName("caller_id_number")
    val callerIdNumber: String,
    @SerializedName("telnyx_session_id")
    val sessionId: String
) : ReceivedResult()