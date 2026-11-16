@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import android.content.pm.PackageInfo
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * t029 regressions: minimal startup provenance under the existing payload `extra`.
 *
 * Asserts exactly which facts may be published (device manufacturer/model/API level, the
 * host app's own package/version/debuggability, the SDK's own build type), that anything
 * unreadable is omitted rather than guessed, and that no unapproved identity/probe data
 * (serial, advertising ID, MAC, fingerprint, installed apps) is ever present.
 */
class AndroidV2StartupFactsTest {

    private fun client(id: String): TelnyxClient {
        val client = mockk<TelnyxClient>(relaxed = true)
        var sink: CallReportV2Ingress? = null
        val sequence = java.util.concurrent.atomic.AtomicLong()
        every { client.nextCallReportV2Sequence() } answers { sequence.incrementAndGet() }
        every { client.callReportV2InstanceId } returns id
        every { client.callReportV2 } answers { sink }
        every { client.callReportV2 = any() } answers { sink = firstArg() }
        return client
    }

    private fun captureWith(client: TelnyxClient, facts: V2StartupFacts, constructing: Boolean = true): AndroidV2Capture =
        AndroidV2Capture(client, constructing = constructing).also { it.startupFactsOverride = facts }

    private suspend fun startupOf(capture: AndroidV2Capture): kotlinx.serialization.json.JsonObject {
        val events = capture.capturedFrames().map { Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject }
        val started = events.firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "sdk_creation_started" }
            ?: events.firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "sdk_created" }
            ?: error("no startup event emitted")
        return started["payload"]!!.jsonObject["extra"]!!.jsonObject["startup"]!!.jsonObject
    }

    private fun allowedKeys(): Set<String> = setOf(
        "manufacturer", "model", "api_level",
        "package", "version_name", "version_code", "debuggable",
        "build_type",
    )

    @Test fun `measured startup facts are published under extra with only allowlisted keys`() = runBlocking {
        val capture = captureWith(client("startup-measured"), V2StartupFacts(
            manufacturer = "Acme", model = "Pixel-Test", apiLevel = 35,
            appPackage = "com.example.host", appVersionName = "1.2.3", appVersionCode = 123, appDebuggable = false))
        try {
            val startup = startupOf(capture)
            assertEquals("Acme", startup["device"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
            assertEquals("Pixel-Test", startup["device"]!!.jsonObject["model"]!!.jsonPrimitive.content)
            assertEquals(35.0, startup["device"]!!.jsonObject["api_level"]!!.jsonPrimitive.double, 0.0)
            assertEquals("com.example.host", startup["app"]!!.jsonObject["package"]!!.jsonPrimitive.content)
            assertEquals("1.2.3", startup["app"]!!.jsonObject["version_name"]!!.jsonPrimitive.content)
            assertEquals(123.0, startup["app"]!!.jsonObject["version_code"]!!.jsonPrimitive.double, 0.0)
            assertEquals(false, startup["app"]!!.jsonObject["debuggable"]!!.jsonPrimitive.boolean)
            assertEquals(BuildConfig.BUILD_TYPE, startup["sdk"]!!.jsonObject["build_type"]!!.jsonPrimitive.content)
            assertTrue(startup.toString().contains("Acme"))
        } finally { capture.shutdown() }
    }

    @Test fun `unreadable host facts are omitted and only the SDK build type remains`() = runBlocking {
        val capture = captureWith(client("startup-omitted"), V2StartupFacts())
        try {
            val startup = startupOf(capture)
            assertEquals(setOf("sdk"), startup.keys)
            assertEquals(BuildConfig.BUILD_TYPE, startup["sdk"]!!.jsonObject["build_type"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }

    @Test fun `live reader omits every unreadable fact instead of guessing`() {
        val facts = V2StartupFactsReader.read(null)
        assertEquals("unavailable context means omission", V2StartupFacts(), facts)
    }

    @Test fun `no unapproved identity or probe data in the startup extra`() = runBlocking {
        val capture = captureWith(client("startup-privacy"), V2StartupFacts(
            manufacturer = "Acme", model = "Pixel-Test", apiLevel = 35, appPackage = "com.example.host",
            appVersionName = "1.2.3", appVersionCode = 123, appDebuggable = true))
        try {
            val startup = startupOf(capture)
            assertTrue("startup groups only device/app/sdk",
                startup.keys.all { it in setOf("device", "app", "sdk") })
            val emittedKeys = buildSet {
                startup.forEach { (_, value) ->
                    if (value is kotlinx.serialization.json.JsonObject) addAll(value.keys)
                }
            }
            assertEquals("only allowlisted startup facts are published", allowedKeys(), emittedKeys)
            val raw = startup.toString().lowercase()
            for (keyword in listOf("serial", "deviceid", "device_id", "advertising", "adid", "mac", "fingerprint", "imei", "installed")) {
                assertTrue("startup extra must not contain '$keyword'", !raw.contains(keyword))
            }
        } finally { capture.shutdown() }
    }

    @Test fun `version code is an exact integer on the wire, including zero and beyond the Int range`() {
        val huge = V2StartupFacts(appVersionCode = 5_000_000_000L).toJson()["app"]!!.jsonObject
        assertEquals(5000000000L, huge["version_code"]!!.jsonPrimitive.long)
        assertTrue("no decimal point on the wire", !huge["version_code"]!!.toString().contains("."))
        val zero = V2StartupFacts(appVersionCode = 0L).toJson()["app"]!!.jsonObject
        assertEquals("measured 0 is preserved", 0L, zero["version_code"]!!.jsonPrimitive.long)
        assertTrue(!zero["version_code"]!!.toString().contains("."))
    }

    @Test fun `legacy version code reads the Int field below API 28`() {
        val info = PackageInfo().apply { versionCode = 123 }
        assertEquals(123L, V2StartupFactsReader.readVersionCode(info, 27))
    }

    @Test fun `negative measured version code is treated as unavailable`() {
        val info = PackageInfo().apply { versionCode = -1 }
        assertNull(V2StartupFactsReader.readVersionCode(info, 27))
    }

    @Test fun `blank and platform UNKNOWN device sentinels are never reported as real facts`() {
        assertNull("unknown".takeReal())
        assertNull(" ".trim().takeReal())
        assertNull("".takeReal())
        assertNull(null.takeReal())
        assertEquals("Acme", "Acme".takeReal())
    }

    @Test fun `startup facts ride the sdk_created path when construction is not in progress`() = runBlocking {
        val capture = captureWith(client("startup-created"), V2StartupFacts(
            manufacturer = "Acme", model = "Pixel-Test", apiLevel = 35, appPackage = "com.example.host"), constructing = false)
        try {
            val events = capture.capturedFrames().map { Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject }
            val created = events.firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "sdk_created" }
                ?: error("sdk_created not emitted")
            val startup = created["payload"]!!.jsonObject["extra"]!!.jsonObject["startup"]!!.jsonObject
            assertEquals("Acme", startup["device"]!!.jsonObject["manufacturer"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
}
