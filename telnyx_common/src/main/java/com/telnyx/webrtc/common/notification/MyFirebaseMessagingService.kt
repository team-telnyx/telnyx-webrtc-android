/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.common.notification

import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent
import timber.log.Timber
import java.util.Locale
import java.util.UUID


/**
 * Result of classifying an FCM data payload into a push receipt.
 *
 * The parser is intentionally free of Android framework types so the receipt
 * decision stays unit-testable; only [PushReceipt.Unrecognized] reaches the
 * legacy notification fallback.
 */
internal sealed class PushReceipt {
    /** A cleanup push such as missed-call or answered-elsewhere; [kind] says which. */
    data class Cleanup(val kind: CallReportV2PushEvent.MessageKind, val callId: UUID?) : PushReceipt()

    /** A push carrying call metadata for an incoming call; [callId] is null unless it parses. */
    data class Incoming(val metadata: PushMetaData, val callId: UUID?) : PushReceipt()

    /** No recognizable message; the legacy notification path handles it. */
    object Unrecognized : PushReceipt()
}

/**
 * Parses an FCM data payload without assuming any key is present, so a push that is
 * missing `metadata` or `message` can never throw out of the FCM callback.
 */
internal object PushReceiptParser {
    fun parse(data: Map<String, String>): PushReceipt {
        val kind = when (data[MESSAGE_KEY]) {
            MISSED_CALL -> CallReportV2PushEvent.MessageKind.MISSED_CALL
            ANSWERED_ELSEWHERE -> CallReportV2PushEvent.MessageKind.ANSWERED_ELSEWHERE
            else -> null
        }
        if (kind != null) {
            return PushReceipt.Cleanup(kind, callIdFromMetadata(data[METADATA_KEY]))
        }
        val metadataJson = data[METADATA_KEY] ?: return PushReceipt.Unrecognized
        val metadata = try {
            Gson().fromJson(metadataJson, PushMetaData::class.java)
        } catch (_: JsonSyntaxException) {
            null
        } ?: return PushReceipt.Unrecognized
        return PushReceipt.Incoming(metadata, callIdFromMetadata(metadataJson))
    }

    /** Returns the call id only when the metadata is a JSON object carrying a valid UUID call id. */
    fun callIdFromMetadata(metadataJson: String?): UUID? {
        if (metadataJson == null) return null
        val metadata = try {
            Gson().fromJson(metadataJson, PushMetaData::class.java)
        } catch (_: JsonSyntaxException) {
            return null
        } ?: return null
        return metadata.callId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
    }

    private const val MESSAGE_KEY = "message"
    private const val METADATA_KEY = "metadata"
    const val MISSED_CALL = "Missed call!"
    const val ANSWERED_ELSEWHERE = "Answered Elsewhere"
}

class MyFirebaseMessagingService : FirebaseMessagingService() {

    private var callNotificationService: CallNotificationService? = null

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val receiptEpochMs = System.currentTimeMillis()
        val receiptMonotonicMs = android.os.SystemClock.elapsedRealtime()
        Timber.d("Firebase message received")
        Timber.d("Message Received From Firebase Priority: ${remoteMessage.priority}")
        Timber.d("Message Received From Firebase: ${remoteMessage.originalPriority}")

        when (val receipt = PushReceiptParser.parse(remoteMessage.data)) {
            is PushReceipt.Cleanup -> {
                Timber.d("Call notification cleanup message received: ${receipt.kind.name.lowercase(Locale.ROOT)}")
                recordPushReceipt(receipt.callId, receipt.kind, remoteMessage.messageId,
                    receiptEpochMs, receiptMonotonicMs)
                dismissIncomingCallNotification()
            }
            is PushReceipt.Incoming -> {
                recordPushReceipt(receipt.callId, messageKind = null, deliveryId = remoteMessage.messageId,
                    receiptEpochMs = receiptEpochMs, receiptMonotonicMs = receiptMonotonicMs)
                // Initialize CallNotificationService if needed
                if (callNotificationService == null) {
                    callNotificationService = CallNotificationService(this, CallNotificationReceiver::class.java)
                }
                callNotificationService?.showIncomingCallNotification(receipt.metadata)
            }
            PushReceipt.Unrecognized -> {
                Timber.d("Push data did not contain a known message or valid call metadata; using legacy notification")
                startLegacyServiceWith(remoteMessage.data[METADATA_DATA_KEY])
            }
        }
    }

    private fun recordPushReceipt(callId: UUID?, messageKind: CallReportV2PushEvent.MessageKind?,
        deliveryId: String?, receiptEpochMs: Long, receiptMonotonicMs: Long) {
        callId ?: return
        runCatching {
            com.telnyx.webrtc.common.TelnyxCommon.getInstance().recordPushTelemetry(
                CallReportV2PushEvent(
                    CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                    messageKind = messageKind,
                    originId = CallReportV2PushEvent.originFor(
                        CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                        deliveryId = deliveryId, messageKind = messageKind),
                    observedAtEpochMs = receiptEpochMs, observedAtMonotonicMs = receiptMonotonicMs))
        }.onFailure { Timber.e(it, "Failed to record push receipt telemetry") }
    }

    private fun startLegacyServiceWith(metadata: String?) {
        val serviceIntent = Intent(this, LegacyCallNotificationService::class.java).apply {
            putExtra(METADATA_DATA_KEY, metadata)
        }
        startMessagingService(serviceIntent)
    }

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the InstanceID token
     * is initially generated so this is where you would retrieve the token.
     */
    override fun onNewToken(token: String) {
        sendRegistrationToServer(token)
    }

    /**
     * Persist token to third-party servers.
     *
     * @param token The new token.
     */
    private fun sendRegistrationToServer(token: String) {
        Timber.d("Firebase registration token updated")
    }

    private fun startMessagingService(serviceIntent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun dismissIncomingCallNotification() {
        CallNotificationService.cancelNotification(this)

        val serviceIntent = Intent(this, LegacyCallNotificationService::class.java).apply {
            putExtra("action", LegacyCallNotificationService.STOP_ACTION)
            action = LegacyCallNotificationService.STOP_ACTION
        }
        stopService(serviceIntent)
    }

    companion object {
        private const val METADATA_DATA_KEY = "metadata"

        const val ANSWER_REQUEST_CODE = 0
        const val REJECT_REQUEST_CODE = 1
        const val OPEN_TO_REPLY_REQUEST_CODE = 2
        const val END_CALL_REQUEST_CODE = 1202

        const val TX_PUSH_METADATA = "tx_push_metadata"

        const val EXT_KEY_DO_ACTION = "ext_key_do_action"
        const val ACT_ANSWER_CALL = "answer"
        const val ACT_REJECT_CALL = "reject"
        const val ACT_OPEN_TO_REPLY = "open_to_reply"
    }
}
