package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName

sealed class DialogParams

data class ByeDialogParams(val callId: String) : DialogParams()

data class CallDialogParams(val useStereo: Boolean = false,
                            val attach: Boolean = false,
                            val video: Boolean = false,
                            val screenShare: Boolean = false,
                            val audio: Boolean = true,
                            val userVariables: ArrayList<Any> = arrayListOf(),
                            @SerializedName("callID")
                            val callId: String,
                            @SerializedName("remote_caller_id_name")
                            val remoteCallerIdName: String = "",
                            @SerializedName("caller_id_number")
                            val callerIdNumber: String = "",
                            @SerializedName("caller_id_name")
                            val callerIdName: String = "",
                            @SerializedName("destination_number")
                            val destinationNumber: String = "")