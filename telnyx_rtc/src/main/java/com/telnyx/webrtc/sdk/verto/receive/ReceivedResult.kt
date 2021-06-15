/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName
import java.util.*


/**
 * Class representations of responses received on the socket connection
 */
sealed class ReceivedResult

/**
 * A login response received by the socket connection
 *
 * @param sessid the session ID provided after logging in.
 */
data class LoginResponse(val sessid: String) : ReceivedResult()

/**
 * A response to an invitation that the user created. Someone has answered your call.
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 */
data class AnswerResponse(
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("sdp")
    val sdp: String
) : ReceivedResult()

/**
 * An invitation response containing the required information
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 * @param callerIdName the name of the person who sent the invitation
 * @param callerIdNumber the number of the person who sent the invitation
 * @param sessionId the Telnyx Session ID on the socket connection.
 */
data class InviteResponse(
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("sdp")
    val sdp: String,
    @SerializedName("callerIdName")
    val callerIdName: String,
    @SerializedName("callerIdNumber")
    val callerIdNumber: String,
    @SerializedName("sessionId")
    val sessionId: String
) : ReceivedResult()