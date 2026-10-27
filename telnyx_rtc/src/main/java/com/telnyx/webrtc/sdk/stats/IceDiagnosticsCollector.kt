/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.stats

/**
 * Collects aggregated ICE/DTLS diagnostics that mirror the `ICE_DIAG` logcat
 * stream (VSUP-295) into the call report.
 *
 * Each entry is a flat map with an "event" discriminator plus the fields the
 * corresponding logcat line already contained. Entries are collected for every
 * call regardless of the debug flag - the payload is bounded by
 * [MAX_ICE_DIAGNOSTICS_PER_CALL], so the added cost is negligible.
 *
 * TURN credential material is redacted in [redactIceServerUrl] so uploaded
 * reports never carry ICE server credentials; the logcat stream keeps full
 * detail.
 */
internal class IceDiagnosticsCollector {

    companion object {
        /** Upper bound on entries per call; keeps the report payload bounded. */
        const val MAX_ICE_DIAGNOSTICS_PER_CALL = 256

        /** Replaces credential material in redacted URLs and query params. */
        const val CREDENTIAL_REPLACEMENT = "<redacted>"

        private const val STUN_SCHEME = "stun:"
        private const val TURN_SCHEME = "turn:"
        private const val TURNS_SCHEME = "turns:"

        private val CREDENTIAL_QUERY_KEYS = setOf(
            "username", "credential", "password", "pass", "token", "key",
        )

        /**
         * Redacts credential material from a `turn:`/`turns:` URL.
         * `turn:user:pass@host?transport=udp` becomes `turn:<redacted>@host?transport=udp`.
         * Credential-bearing query params are replaced with [CREDENTIAL_REPLACEMENT].
         * `stun:` URLs carry no credentials and are returned unchanged.
         */
        fun redactIceServerUrl(url: String): String {
            val trimmed = url.trim()
            val scheme = when {
                trimmed.startsWith(TURNS_SCHEME) -> TURNS_SCHEME
                trimmed.startsWith(TURN_SCHEME) -> TURN_SCHEME
                trimmed.startsWith(STUN_SCHEME) -> STUN_SCHEME
                else -> return trimmed
            }
            val rest = trimmed.substring(scheme.length)
            val authority = rest.substringBefore('?')
            val query = if (rest.contains('?')) rest.substringAfter('?') else null

            val redactedAuthority = if (authority.contains('@')) {
                val userinfo = authority.substringBefore('@')
                val host = authority.substringAfter('@')
                userinfo.substringBefore(':') + ":" + CREDENTIAL_REPLACEMENT + "@" + host
            } else {
                authority
            }
            val redactedQuery = query
                ?.split('&')
                ?.joinToString("&") { param ->
                    val idx = param.indexOf('=')
                    if (idx >= 0 && param.substring(0, idx).lowercase() in CREDENTIAL_QUERY_KEYS) {
                        param.substring(0, idx) + "=" + CREDENTIAL_REPLACEMENT
                    } else {
                        param
                    }
                }

            return buildString {
                append(scheme)
                append(redactedAuthority)
                if (redactedQuery != null) {
                    append('?')
                    append(redactedQuery)
                }
            }
        }
    }

    private val entries = mutableListOf<Map<String, Any?>>()

    /** Snapshot of the entries collected so far. */
    val snapshot: List<Map<String, Any?>>
        get() = synchronized(entries) { entries.toList() }

    /** Number of entries accepted so far. */
    val size: Int
        get() = synchronized(entries) { entries.size }

    /** True once the per-call cap has been reached and further entries are dropped. */
    val isFull: Boolean
        get() = size >= MAX_ICE_DIAGNOSTICS_PER_CALL

    /** Adds one diagnostic entry. Silently drops once the per-call cap is reached. */
    fun add(entry: Map<String, Any?>) {
        synchronized(entries) {
            if (entries.size < MAX_ICE_DIAGNOSTICS_PER_CALL) {
                entries.add(entry)
            }
        }
    }
}
