package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.lib.RTCStatsReport
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Bounded, change-deduped observation of the native RTCStatsReport playout surface.
 *
 * Reports only allowlisted stat-family presence and the presence/type (never values) of
 * media-playout members, especially the kind/mediaType qualification. A consumer can
 * distinguish: no native media-playout row, a row that never qualifies as audio, or a
 * qualified row whose numeric playout members are absent/non-numeric. It is an
 * observation only: it never claims a capability is unsupported, and a later availability
 * change is reported as a new observation, bounded by per-call change-dedup. No raw stat
 * dump, no identifiers, addresses, SDP, credentials or audio content.
 */
internal object V2PlayoutDiagnostic {
    /** Stat families whose mere presence may be reported (booleans only). */
    private val FAMILIES = listOf("media-playout", "media-source", "inbound-rtp", "outbound-rtp", "transport")

    /** media-playout members whose presence/type may be reported. Values are never reported. */
    private val PLAYOUT_FIELDS = listOf(
        "totalSamplesCount",
        "synthesizedSamplesDuration",
        "totalPlayoutDelay",
        "synthesizedSamplesEvents",
    )

    private const val MAX_ROWS_OBSERVED = 4

    fun observe(report: RTCStatsReport): JsonObject {
        val rows = report.statsMap.values
        val families = buildJsonObject {
            FAMILIES.forEach { family -> put(family, rows.any { it.type == family }) }
        }
        val playoutRows = rows.filter { it.type == "media-playout" }
        val observed = playoutRows.take(MAX_ROWS_OBSERVED)
        val representative = observed.firstOrNull {
            it.members["kind"] == "audio" || it.members["mediaType"] == "audio"
        } ?: observed.firstOrNull()
        val mediaPlayout = buildJsonObject {
            put("rows", playoutRows.size.coerceAtMost(MAX_ROWS_OBSERVED))
            if (playoutRows.size > MAX_ROWS_OBSERVED) put("rows_capped", true)
            put("audio_qualified_rows", observed.count {
                it.members["kind"] == "audio" || it.members["mediaType"] == "audio"
            })
            if (representative != null) {
                put("qualifier", buildJsonObject {
                    put("kind", qualifier(representative.members.containsKey("kind"), representative.members["kind"]))
                    put("media_type", qualifier(representative.members.containsKey("mediaType"), representative.members["mediaType"]))
                })
                put("fields", buildJsonObject {
                    PLAYOUT_FIELDS.forEach { field ->
                        put(field, valueType(representative.members.containsKey(field), representative.members[field]))
                    }
                })
            }
        }
        return buildJsonObject {
            put("families", families)
            put("media_playout", mediaPlayout)
        }
    }

    /** Presence/type only — the kind/mediaType value itself is never reported. */
    private fun qualifier(present: Boolean, value: Any?): JsonObject = buildJsonObject {
        put("present", present)
        if (present) put("type", typeName(value))
    }

    private fun valueType(present: Boolean, value: Any?): String = when {
        !present -> "absent"
        value === null -> "null"
        value is Number -> if (value.toDouble().isFinite()) "number" else "non-finite-number"
        value is String -> "string"
        value is Boolean -> "boolean"
        else -> "other"
    }

    private fun typeName(value: Any?): String = when (value) {
        null -> "null"
        is Number -> "number"
        is String -> "string"
        is Boolean -> "boolean"
        else -> "other"
    }
}
