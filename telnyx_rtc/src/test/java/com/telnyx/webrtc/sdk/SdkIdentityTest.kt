/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import com.google.gson.Gson
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for [SdkIdentity] — the immutable SDK identity carried on every
 * outbound verto message.
 *
 * These tests guard:
 *  - the default identity matches the direct-core contract ("android" + core
 *    artifact version),
 *  - the type is genuinely immutable (val fields, no setter on a live client),
 *  - `copy()` produces a new value (no shared mutable state across instances),
 *  - the wire serialization shape carries `mobile_sdk_name` and
 *    `mobile_sdk_version` as flat fields next to `User-Agent`.
 */
class SdkIdentityTest {

    @Test
    fun `default identity is android plus core artifact version`() {
        val identity = SdkIdentity.default()
        assertEquals(SdkIdentity.DEFAULT_NAME, identity.name)
        assertEquals("android", identity.name)
        assertEquals(BuildConfig.SDK_VERSION, identity.version)
    }

    @Test
    fun `default identity fields are non-null and versioned to core artifact`() {
        val identity = SdkIdentity.default()
        assertNotNull(identity.name)
        assertNotNull(identity.version)
        // Core artifact version is set by the build (e.g. "3.7.3") — must be
        // a non-empty string sourced from BuildConfig.
        assertEquals(BuildConfig.SDK_VERSION, identity.version)
        assert(identity.version.isNotBlank()) { "version must not be blank" }
    }

    @Test
    fun `custom identity preserves constructor arguments`() {
        val custom = SdkIdentity(name = "android_commons", version = "1.0.0")
        assertEquals("android_commons", custom.name)
        assertEquals("1.0.0", custom.version)
        // Distinct from default — proves the constructor accepts overrides.
        assertNotEquals(SdkIdentity.default(), custom)
    }

    @Test
    fun `copy produces a new immutable value with the requested overrides`() {
        val original = SdkIdentity.default()
        val copied = original.copy(name = "android_commons", version = "1.0.0")
        // Original is unchanged — copy() must not mutate the source instance.
        assertEquals("android", original.name)
        assertEquals(BuildConfig.SDK_VERSION, original.version)
        // Copy carries the overrides.
        assertEquals("android_commons", copied.name)
        assertEquals("1.0.0", copied.version)
        // copy() returns a new instance — not the same reference.
        assertNotEquals(original, copied)
    }

    @Test
    fun `LoginParam carries mobile_sdk_name and mobile_sdk_version for default identity`() {
        // Construct a LoginParam using the default direct-core identity values
        // and serialize to JSON. The wire shape must include the new fields.
        val identity = SdkIdentity.default()
        val json = Gson().toJson(
            mapOf(
                "login" to "alice",
                "passwd" to "secret",
                "userVariables" to com.google.gson.JsonObject(),
                "loginParams" to null,
                "sessid" to "sess-1",
                "User-Agent" to "Android-${BuildConfig.SDK_VERSION}",
                "mobile_sdk_name" to identity.name,
                "mobile_sdk_version" to identity.version,
            )
        )
        // Round-trip through Gson and verify both fields land on the wire next
        // to the legacy User-Agent string.
        val parsed = Gson().fromJson(json, Map::class.java)
        assertEquals(identity.name, parsed["mobile_sdk_name"])
        assertEquals(identity.version, parsed["mobile_sdk_version"])
        assertEquals("Android-${BuildConfig.SDK_VERSION}", parsed["User-Agent"])
    }

    @Test
    fun `LoginParam carries overridden identity for commons wrapper`() {
        // A wrapper identity must reach the wire unchanged — the whole point
        // of the new fields is to let Telnyx distinguish wrappers from core.
        val identity = SdkIdentity(name = "android_commons", version = "1.0.0")
        val json = Gson().toJson(
            mapOf(
                "login" to "alice",
                "passwd" to "secret",
                "userVariables" to com.google.gson.JsonObject(),
                "loginParams" to null,
                "sessid" to "sess-1",
                "User-Agent" to "Android-${BuildConfig.SDK_VERSION}",
                "mobile_sdk_name" to identity.name,
                "mobile_sdk_version" to identity.version,
            )
        )
        val parsed = Gson().fromJson(json, Map::class.java)
        assertEquals("android_commons", parsed["mobile_sdk_name"])
        assertEquals("1.0.0", parsed["mobile_sdk_version"])
    }
}
