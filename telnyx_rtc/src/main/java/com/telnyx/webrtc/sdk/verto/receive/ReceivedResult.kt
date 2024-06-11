/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.CustomHeaders
import com.telnyx.webrtc.sdk.utilities.parseObject
import com.telnyx.webrtc.sdk.utilities.toJsonString
import kotlinx.parcelize.Parceler
import kotlinx.parcelize.Parcelize
import java.util.*
import kotlin.collections.ArrayList

/**
 * Class representations of responses received on the socket connection
 */
sealed class ReceivedResult


@Parcelize
data class DisablePushResponse(
    @SerializedName("message")
    val success: Boolean,
    @SerializedName("message")
    val message: String
) : ReceivedResult(), Parcelable {

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
@Parcelize
data class LoginResponse(
    @SerializedName("sessid")
    val sessid: String
) : ReceivedResult(), Parcelable


data class ByeResponse(
    @SerializedName("sessid")
    val callId : UUID
) : ReceivedResult()

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
    val sdp: String,
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf()
) : ReceivedResult(), Parcelable {
    private companion object : Parceler<AnswerResponse> {
        override fun AnswerResponse.write(parcel: Parcel, flags: Int) {
            parcel.writeString(sdp)
            parcel.writeString(callId.toString())
            parcel.writeString(customHeaders.toJsonString())
        }

        override fun create(parcel: Parcel): AnswerResponse {
            return AnswerResponse(
                callId = UUID.fromString(parcel.readString()),
                sdp = parcel.readString()!!,
                customHeaders = parcel.readString()?.parseObject<ArrayList<CustomHeaders>>() ?: arrayListOf()
            )
        }
    }

}

/**
 * An invitation response containing the required information
 *
 * @param callId a unique UUID that represents each ongoing call.
 * @param sdp the Session Description Protocol that is received as a part of the answer to the call.
 * @param callerIdName the name of the person who sent the invitation
 * @param callerIdNumber the number of the person who sent the invitation
 * @param sessid the Telnyx Session ID on the socket connection.
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
    @SerializedName("sessid")
    val sessid: String,
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf()
) : ReceivedResult(), Parcelable {
    private companion object : Parceler<InviteResponse> {
        override fun InviteResponse.write(parcel: Parcel, flags: Int) {
            parcel.writeString(sdp)
            parcel.writeString(callId.toString())
            parcel.writeString(callerIdNumber)
            parcel.writeString(callerIdName)
            parcel.writeString(sessid)
            parcel.writeString(customHeaders.toJsonString())
        }

        override fun create(parcel: Parcel): InviteResponse {
            return InviteResponse(
                callId = UUID.fromString(parcel.readString()),
                sdp = parcel.readString()!!,
                callerIdNumber = parcel.readString()!!,
                callerIdName = parcel.readString()!!,
                sessid = parcel.readString()!!,
                customHeaders = parcel.readString()?.parseObject<ArrayList<CustomHeaders>>() ?: arrayListOf()
            )
        }
    }
}

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
