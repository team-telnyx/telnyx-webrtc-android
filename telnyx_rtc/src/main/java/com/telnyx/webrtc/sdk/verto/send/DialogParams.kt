/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.CustomHeaders
import com.telnyx.webrtc.sdk.model.AudioCodec
import java.util.*
import kotlin.collections.ArrayList

sealed class DialogParams

data class ByeDialogParams(val callId: UUID) : DialogParams()

data class CallDialogParams(
    val useStereo: Boolean = false,
    val attach: Boolean = false,
    val video: Boolean = false,
    val screenShare: Boolean = false,
    val audio: Boolean = true,
    val userVariables: ArrayList<Any> = arrayListOf(),
    @SerializedName("clientState")
    val clientState: String = "",
    @SerializedName("callID")
    val callId: UUID,
    @SerializedName("remote_caller_id_name")
    val remoteCallerIdName: String = "",
    @SerializedName("caller_id_number")
    val callerIdNumber: String = "",
    @SerializedName("caller_id_name")
    val callerIdName: String = "",
    @SerializedName("destination_number")
    val destinationNumber: String = "",
    @SerializedName("custom_headers")
    val customHeaders: ArrayList<CustomHeaders> = arrayListOf(),
    @SerializedName("preferred_codecs")
    val preferredCodecs: List<String>? = null
)
