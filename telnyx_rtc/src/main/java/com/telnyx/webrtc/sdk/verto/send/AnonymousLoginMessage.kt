/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

import com.google.gson.annotations.SerializedName
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig

/**
 * Data class representing an anonymous login message for AI assistant connections.
 *
 * Emits the legacy `User-Agent` `UserAgent` block unchanged plus the new
 * top-level `mobile_sdk_name` / `mobile_sdk_version` identity fields. Direct
 * core callers use the defaults (`"android"` + core artifact version); higher
 * level wrappers pass overridden values via the `mobileSdkName` /
 * `mobileSdkVersion` constructor parameters.
 *
 * @param targetType the type of target (defaults to "ai_assistant")
 * @param targetId the unique identifier of the target assistant
 * @param targetVersionId optional version ID of the target
 * @param conversationId optional conversation ID to join an existing conversation
 * @param userVariables optional user variables to include
 * @param reconnection whether this is a reconnection attempt
 * @param userAgent user agent information containing SDK version and platform data
 * @param mobileSdkName mobile SDK identifier (defaults to "android" for core usage)
 * @param mobileSdkVersion mobile SDK version (defaults to core artifact version)
 * @param sessid the session ID for the connection
 */
data class AnonymousLoginParams(
    @SerializedName("target_type")
    val targetType: String = "ai_assistant",
    @SerializedName("target_id")
    val targetId: String,
    @SerializedName("target_version_id")
    val targetVersionId: String? = null,
    @SerializedName("conversation_id")
    val conversationId: String? = null,
    @SerializedName("userVariables")
    val userVariables: Map<String, Any>? = null,
    @SerializedName("reconnection")
    val reconnection: Boolean = false,
    @SerializedName("User-Agent")
    val userAgent: UserAgent,
    @SerializedName("mobile_sdk_name")
    val mobileSdkName: String = "android",
    @SerializedName("mobile_sdk_version")
    val mobileSdkVersion: String = BuildConfig.SDK_VERSION,
    @SerializedName("sessid")
    val sessid: String? = null,
) : ParamRequest()

/**
 * Data class representing user agent information for anonymous login
 *
 * @param sdkVersion the version of the SDK
 * @param data platform-specific user agent data
 */
data class UserAgent(
    @SerializedName("sdkVersion")
    val sdkVersion: String = BuildConfig.SDK_VERSION,
    @SerializedName("data")
    val data: String = "Android-${BuildConfig.SDK_VERSION}"
)
