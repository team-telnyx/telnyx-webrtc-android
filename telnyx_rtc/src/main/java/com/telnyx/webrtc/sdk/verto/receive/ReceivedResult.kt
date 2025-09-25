/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.CustomHeaders
import com.telnyx.webrtc.sdk.verto.send.AiConversationParams
import java.util.*

/**
 * Class representations of responses received on the socket connection
 */
sealed class ReceivedResult



data class DisablePushResponse(
    @SerializedName("message")
    val success: Boolean,
    @SerializedName("message")
    val message: String
) : ReceivedResult() {

    companion object {
        // Refactor for backend to send a boolean instead of a string
        const val SUCCESS_KEY = "success"
    }
}

/**
 * A login response received by the socket connection
 *
 * @param sessid the session ID provided after logging in.
 */
data class LoginResponse(
    @SerializedName("sessid")
    val sessid: String
) : ReceivedResult()


data class ByeResponse(
    @SerializedName("callID")
    val callId : UUID,
    @SerializedName("cause")
    val cause: String? = null,
    @SerializedName("causeCode")
    val causeCode: Int? = null,
    @SerializedName("sipCode")
    val sipCode: Int? = null,
    @SerializedName("sipReason")
    val sipReason: String? = null
) : ReceivedResult()

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
    val sdp: String,
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf()
) : ReceivedResult()

/**
 * An invitation response containing the required information
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 * @param callerIdName the name of the person who sent the invitation
 * @param callerIdNumber the number of the person who sent the invitation
 * @param sessid the Telnyx Session ID on the socket connection.
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
    @SerializedName("sessid")
    val sessid: String,
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf()
) : ReceivedResult()

data class RingingResponse(
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("callerIdName")
    val callerIdName: String,
    @SerializedName("callerIdNumber")
    val callerIdNumber: String,
    @SerializedName("sessid")
    val sessid: String,
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf()
) : ReceivedResult()


data class MediaResponse(
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("callerIdName")
    val callerIdName: String,
    @SerializedName("callerIdNumber")
    val callerIdNumber: String,
    @SerializedName("sessid")
    val sessid: String
) : ReceivedResult()

/**
 * A response containing AI conversation data
 *
 * @param aiConversationParams the AI conversation parameters containing transcript and widget settings
 */
data class AiConversationResponse(
    @SerializedName("params")
    val aiConversationParams: AiConversationParams? = null
) : ReceivedResult()

/**
 * A response to an updateMedia modify request containing the new remote SDP for ICE renegotiation
 *
 * @param action the action type, should be "updateMedia"
 * @param callId the unique UUID of the call being renegotiated
 * @param sdp the new remote Session Description Protocol for ICE restart
 */
data class UpdateMediaResponse(
    @SerializedName("action")
    val action: String,
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("sdp")
    val sdp: String
) : ReceivedResult()
