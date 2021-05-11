/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

sealed class ParamRequest

data class LoginParam(val login_token: String?, val login: String?,
                      val passwd: String?, val fcmDeviceId: String?, val userVariables: ArrayList<Any>?,
                      val loginParams: ArrayList<Any>?
                      ) : ParamRequest()

data class CallParams(val sessionId: String, val sdp: String,
                      val dialogParams: CallDialogParams
                      ) : ParamRequest()

data class ByeParams(val sessid: String, val causeCode: Int,
                     val cause: String, val dialogParams: ByeDialogParams
                     ) : ParamRequest()

data class ModifyParams(val sessid: String, val action: String,
                        val dialogParams: CallDialogParams
                        ) : ParamRequest()
