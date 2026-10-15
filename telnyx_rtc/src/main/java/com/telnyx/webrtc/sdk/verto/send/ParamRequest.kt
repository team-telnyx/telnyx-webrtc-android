/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import java.util.UUID

sealed class ParamRequest

/**
 * Login message params.
 *
 * Emits the legacy `User-Agent` field unchanged (still "Android-<core version>")
 * plus the new `mobile_sdk_name` / `mobile_sdk_version` identity fields. Direct
 * core callers use the defaults (`"webrtc-android"` + core artifact version); higher
 * level wrappers (e.g. `telnyx_common`) pass overridden values via the
 * `mobileSdkName` / `mobileSdkVersion` parameters.
 */
data class LoginParam(
    @SerializedName("login_token")
    val loginToken: String?,
    val login: String?,
    val passwd: String?,
    val userVariables: JsonObject,
    val loginParams: Map<Any, Any>?,
    val sessid: String,
    @SerializedName("User-Agent")
    val userAgent: String = "Android-" + BuildConfig.SDK_VERSION,
    @SerializedName("mobile_sdk_name")
    val mobileSdkName: String = "webrtc-android",
    @SerializedName("mobile_sdk_version")
    val mobileSdkVersion: String = BuildConfig.SDK_VERSION,
) : ParamRequest()

data class StatPrams(
    val type: String = "debug_report_data",
    @SerializedName("debug_report_id")
    val debugReportId: String = UUID.randomUUID().toString(),
    @SerializedName("debug_report_data")
    val reportData: JsonObject,
    @SerializedName("debug_report_version")
    val debugReportVersion: Int = 1,
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),
    val jsonrpc: String = "2.0"
) : ParamRequest()

data class InitiateOrStopStatPrams(
    val type: String = "debug_report_stop",
    @SerializedName("debug_report_id")
    val debugReportId: String = UUID.randomUUID().toString(),
    @SerializedName("debug_report_version")
    val debugReportVersion: Int = 1,
    @SerializedName("id")
    val id: String = UUID.randomUUID().toString(),
    val jsonrpc: String = "2.0"
) : ParamRequest()

/**
 * Call (invite / answer) message params. Emits legacy `User-Agent` unchanged
 * plus the new `mobile_sdk_name` / `mobile_sdk_version` identity fields.
 */
data class CallParams(
    val sessid: String,
    val sdp: String,
    @SerializedName("User-Agent")
    val userAgent: String = "Android-" + BuildConfig.SDK_VERSION,
    @SerializedName("mobile_sdk_name")
    val mobileSdkName: String = "webrtc-android",
    @SerializedName("mobile_sdk_version")
    val mobileSdkVersion: String = BuildConfig.SDK_VERSION,
    @SerializedName("answered_device_token")
    val answeredDeviceToken: String? = null,
    val dialogParams: CallDialogParams,
    val trickle: Boolean? = null,
) : ParamRequest()

data class ByeParams(
    val sessid: String,
    val causeCode: Int,
    val cause: String,
    val dialogParams: ByeDialogParams
) : ParamRequest()

data class DisablePushParams(
    @SerializedName("user")
    val user: String,
    @SerializedName("User-Agent")
    val userVariables: UserVariables
) : ParamRequest()

data class TokenDisablePushParams(
    @SerializedName("login_token")
    val loginToken: String,
    @SerializedName("User-Agent")
    val userVariables: UserVariables
) : ParamRequest()

/**
 * Attach call (recovery / resume) message params. Emits legacy `User-Agent`
 * unchanged plus the new `mobile_sdk_name` / `mobile_sdk_version` identity
 * fields so reconnection / resume traffic carries the same identity as the
 * originating login.
 */
data class AttachCallParams(
    @SerializedName("userVariables")
    val userVariables: AttachUserVariables,
    @SerializedName("User-Agent")
    val userAgent: String = "Android-" + BuildConfig.SDK_VERSION,
    @SerializedName("mobile_sdk_name")
    val mobileSdkName: String = "webrtc-android",
    @SerializedName("mobile_sdk_version")
    val mobileSdkVersion: String = BuildConfig.SDK_VERSION,
) : ParamRequest()


data class AttachUserVariables(
    @SerializedName("push_notification_environment")
    val pushNotificationEnvironment: String = if (BuildConfig.DEBUG) "development" else "production",
    @SerializedName("push_notification_provider")
    val pushNotificationProvider: String = "webrtc-android",
)

data class UserVariables(
    @SerializedName("push_device_token")
    val pushDeviceToken: String,
    @SerializedName("push_notification_provider")
    val pushNotificationProvider: String = "webrtc-android",
)

data class ModifyParams(
    val sessid: String,
    val action: String,
    val dialogParams: CallDialogParams,
    val sdp: String? = null
) : ParamRequest()

data class InfoParams(
    val sessid: String,
    val dtmf: String,
    val dialogParams: CallDialogParams
) : ParamRequest()

data class StateParams(val state: String?) : ParamRequest()

data class CandidateParams(
    val sessid: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val dialogParams: CandidateDialogParams
) : ParamRequest()

data class EndOfCandidatesParams(
    val sessid: String,
    val dialogParams: CandidateDialogParams
) : ParamRequest()
