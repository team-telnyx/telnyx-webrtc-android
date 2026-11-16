package com.telnyx.webrtc.sdk.observability

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minimal startup provenance facts for the sdk_creation_started / sdk_created `extra`
 * (contract `extra` is stored whole and is not parsed by the report). Only facts this
 * client can actually read: device manufacturer/model, Android API level, the host app's
 * own package/version and debuggability, and the SDK's own build type. Anything that is
 * unavailable, blank, or a platform placeholder is omitted, never guessed. No device
 * serial, advertising ID, MAC address, hardware fingerprint, or unrelated installed-app
 * access.
 */
internal data class V2StartupFacts(
    val manufacturer: String? = null,
    val model: String? = null,
    val apiLevel: Int? = null,
    val appPackage: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val appDebuggable: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        val device = buildJsonObject {
            manufacturer?.let { put("manufacturer", it) }
            model?.let { put("model", it) }
            apiLevel?.let { put("api_level", it) }
        }
        val app = buildJsonObject {
            appPackage?.let { put("package", it) }
            appVersionName?.let { put("version_name", it) }
            // Exact JSON integer (no decimals), including a measured 0.
            appVersionCode?.let { put("version_code", JsonPrimitive(it)) }
            appDebuggable?.let { put("debuggable", it) }
        }
        if (device.keys.isNotEmpty()) put("device", device)
        if (app.keys.isNotEmpty()) put("app", app)
        // The SDK's own build type comes from its own authoritative build input and is
        // distinct from the host app's debuggability.
        if (BuildConfig.BUILD_TYPE.isNotBlank()) {
            put("sdk", buildJsonObject { put("build_type", BuildConfig.BUILD_TYPE) })
        }
    }

    fun hasNoMeasuredFacts(): Boolean = this == V2StartupFacts()
}

/** Reads the startup facts from the host environment; every failure means omission. */
internal object V2StartupFactsReader {
    fun read(context: Context?): V2StartupFacts {
        // Device facts are static OS values; blank or the platform's UNKNOWN sentinel means
        // the fact is not a real measurement and is omitted.
        val manufacturer = Build.MANUFACTURER?.trim().takeReal()
        val model = Build.MODEL?.trim().takeReal()
        val apiLevel = Build.VERSION.SDK_INT.takeIf { it > 0 }

        var appPackage: String? = null
        var versionName: String? = null
        var versionCode: Long? = null
        var debuggable: Boolean? = null
        try {
            val appContext = context?.applicationContext
            val packageName = appContext?.packageName?.trim().takeReal()
            if (appContext != null && packageName != null) {
                val packageInfo: PackageInfo? = appContext.packageManager?.getPackageInfo(packageName, 0)
                appPackage = packageInfo?.packageName?.trim().takeReal() ?: packageName
                versionName = packageInfo?.versionName?.trim().takeReal()
                versionCode = packageInfo?.let { readVersionCode(it, Build.VERSION.SDK_INT) }
                debuggable = packageInfo?.applicationInfo?.let {
                    (it.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                }
            }
        } catch (_: RuntimeException) {
            // A missing application context or package state means omit, not guess.
        } catch (_: PackageManager.NameNotFoundException) {
            // The host app package is not resolvable here; omit app facts.
        }
        return V2StartupFacts(manufacturer, model, apiLevel, appPackage, versionName, versionCode, debuggable)
    }

    /**
     * Version code: exact `Long` via getLongVersionCode on API >= 28 (which can exceed the
     * Int range), the legacy Int field below. A measured 0 is preserved; a negative value is
     * treated as unavailable and omitted.
     */
    internal fun readVersionCode(packageInfo: PackageInfo, apiLevel: Int): Long? {
        val measured: Long = if (apiLevel >= 28) packageInfo.longVersionCode
        else @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        return measured.takeIf { it >= 0 }
    }
}

/** Blank and the platform's UNKNOWN placeholder are not real facts. */
internal fun String?.takeReal(): String? = this?.takeIf { it.isNotBlank() && it != Build.UNKNOWN }
