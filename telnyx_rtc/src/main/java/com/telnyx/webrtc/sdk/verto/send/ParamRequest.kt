/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig

sealed class ParamRequest

data class LoginParam(val login_token: String?, val login: String?,
                      val passwd: String?,
                      val push_device_token: String?,
                      val push_notification_provider: String = "firebase",
                      val userVariables: ArrayList<Any>?,
                      val loginParams: ArrayList<Any>?
                      ) : ParamRequest()

data class CallParams(val sessionId: String,
                      val sdp: String,
                      @SerializedName("User-Agent")
                      val userAgent: String = "Android-"+ BuildConfig.SDK_VERSION.toString(),
                      val dialogParams: CallDialogParams
                      ) : ParamRequest()

data class ByeParams(val sessionId: String, val causeCode: Int,
                     val cause: String, val dialogParams: ByeDialogParams
                     ) : ParamRequest()

data class ModifyParams(val sessionId: String, val action: String,
                        val dialogParams: CallDialogParams
                        ) : ParamRequest()

data class InfoParams(val sessionId: String, val dtmf: String,
                      val dialogParams: CallDialogParams) : ParamRequest()

data class StateParams(val state: String?) : ParamRequest()

