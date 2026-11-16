package com.telnyx.webrtc.sdk.observability

import android.os.SystemClock
import java.util.UUID

/**
 * An observed push milestone, never the payload, FCM token or a guessed delivery time.
 *
 * [messageKind] distinguishes the push message that carried the event (for example
 * cleanup pushes); it stays null for ordinary incoming-call pushes and is optional
 * supporting detail, not a wire contract change.
 */
data class CallReportV2PushEvent(
    val fact: Fact,
    val callId: UUID,
    val action: Action? = null,
    val messageKind: MessageKind? = null,
    val originId: UUID = originFor(fact, callId, action, messageKind = messageKind),
    val observedAtEpochMs: Long = System.currentTimeMillis(),
    val observedAtMonotonicMs: Long = SystemClock.elapsedRealtime(),
    val reason: Reason? = null
) {
    companion object {
        /** Stable source occurrence identity; duplicate PendingIntent delivery is not another action. */
        fun originFor(fact: Fact, callId: UUID, action: Action? = null, deliveryId: String? = null,
                      messageKind: MessageKind? = null): UUID =
            UUID.nameUUIDFromBytes((callId.toString() + ":" + fact.name.lowercase(java.util.Locale.ROOT) +
                ":" + (action?.name?.lowercase(java.util.Locale.ROOT) ?: deliveryId.orEmpty()) +
                messageKind?.let { kind -> ":" + kind.name.lowercase(java.util.Locale.ROOT) }.orEmpty()).toByteArray(Charsets.UTF_8))
    }
    enum class Fact { PUSH_RECEIVED, NOTIFICATION_ACTION, PUSH_METADATA_PROCESSED, PUSH_ATTACH_STARTED, PUSH_ATTACH_SUCCEEDED, PUSH_ATTACH_FAILED }
    enum class Action { ANSWER, REJECT, END }
    enum class MessageKind { INCOMING_CALL, MISSED_CALL, ANSWERED_ELSEWHERE }
    enum class Reason { SERVER_ERROR, INVITE_TIMEOUT }
}
