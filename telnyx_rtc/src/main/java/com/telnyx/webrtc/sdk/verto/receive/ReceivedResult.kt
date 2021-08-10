/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.verto.send.StateParams
import kotlinx.android.parcel.Parcelize
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
@Parcelize
data class LoginResponse(
    @SerializedName("sessid")
    val sessid: String
    ) : ReceivedResult(), Parcelable

/**
 * A response to an invitation that the user created. Someone has answered your call.
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 */
@Parcelize
data class AnswerResponse(
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("sdp")
    val sdp: String
) : ReceivedResult(), Parcelable

/**
 * An invitation response containing the required information
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 * @param callerIdName the name of the person who sent the invitation
 * @param callerIdNumber the number of the person who sent the invitation
 * @param sessionId the Telnyx Session ID on the socket connection.
 */
@Parcelize
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
) : ReceivedResult(), Parcelable

/**
 * A Gateway response as a result of a login request
 *
 * @param params the gateway response parameters
 * @param sessid the session ID provided during the login process
 */
data class StateResponse(
    @SerializedName("params")
    val params: StateParams?,
    @SerializedName("sessid")
    val sessid: String
) : ReceivedResult()

