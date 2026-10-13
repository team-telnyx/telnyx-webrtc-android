/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig

/**
 * Immutable SDK identity carried on every outbound verto message.
 *
 * Direct core usage defaults to [DEFAULT_NAME] ("android") with the core artifact
 * version ([BuildConfig.SDK_VERSION]). Higher-level wrappers (e.g.
 * `telnyx_common`) override these defaults with their own published identity so
 * Telnyx can distinguish wrapper traffic from direct core traffic on the wire.
 *
 * The class is intentionally a Kotlin `data class` with `val` properties only:
 * there is no setter path on a live [TelnyxClient] and no field is mutable, so
 * consumer code cannot accidentally change identity mid-session. Use
 * [SdkIdentity.copy] only at construction time when assembling a new client.
 *
 * @property name short SDK identifier emitted as `mobile_sdk_name` (e.g.
 *   "android", "android_commons").
 * @property version exact published version of the SDK emitting traffic,
 *   emitted as `mobile_sdk_version`.
 */
data class SdkIdentity(
    val name: String,
    val version: String,
) {
    companion object {
        /** Identity for direct core (telnyx_rtc) usage. */
        val DEFAULT_NAME: String = "android"

        /**
         * Returns the default identity for direct core usage: name = "android"
         * and version = [BuildConfig.SDK_VERSION] (the core artifact version
         * sourced from the telnyx_rtc publishing/build metadata).
         */
        fun default(): SdkIdentity = SdkIdentity(
            name = DEFAULT_NAME,
            version = BuildConfig.SDK_VERSION,
        )
    }
}
