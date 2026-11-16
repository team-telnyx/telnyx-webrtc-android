package com.telnyx.webrtc.common.notification

import com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FCM receipt decision must never depend on a data key being present:
 * the production defect was `JSONObject.getString` throwing on missing
 * `metadata`/`message` keys and crashing the push callback before any
 * cleanup or notification could happen.
 */
class PushReceiptParserTest {

    @Test
    fun `missing metadata and message keys is unrecognized instead of throwing`() {
        assertTrue(PushReceiptParser.parse(emptyMap()) is PushReceipt.Unrecognized)
    }

    @Test
    fun `cleanup message without metadata is a cleanup receipt with no call id`() {
        val receipt = PushReceiptParser.parse(mapOf("message" to "Missed call!"))
        assertEquals(PushReceipt.Cleanup(CallReportV2PushEvent.MessageKind.MISSED_CALL, null), receipt)
    }

    @Test
    fun `answered elsewhere message is its own cleanup kind`() {
        val callId = java.util.UUID.randomUUID()
        val receipt = PushReceiptParser.parse(mapOf(
            "message" to "Answered Elsewhere",
            "metadata" to """{"call_id":"$callId"}"""
        ))
        assertEquals(PushReceipt.Cleanup(CallReportV2PushEvent.MessageKind.ANSWERED_ELSEWHERE, callId), receipt)
    }

    @Test
    fun `cleanup with malformed metadata still cleans up without a call id`() {
        val receipt = PushReceiptParser.parse(mapOf("message" to "Missed call!", "metadata" to "not-json"))
        assertEquals(PushReceipt.Cleanup(CallReportV2PushEvent.MessageKind.MISSED_CALL, null), receipt)
    }

    @Test
    fun `message takes precedence over metadata for cleanup classification`() {
        val receipt = PushReceiptParser.parse(mapOf(
            "message" to "Missed call!",
            "metadata" to """{"call_id":"not-a-uuid"}"""
        ))
        assertEquals(PushReceipt.Cleanup(CallReportV2PushEvent.MessageKind.MISSED_CALL, null), receipt)
    }

    @Test
    fun `non cleanup message with valid metadata is an incoming receipt`() {
        val receipt = PushReceiptParser.parse(mapOf(
            "message" to "Telnyx VoIP Push Notification Tester",
            "metadata" to """{"call_id":"87654321-dcba-4321-dcba-0987654321fe","caller_name":"Alice"}"""
        ))
        assertEquals(true, receipt is PushReceipt.Incoming)
    }

    @Test
    fun `metadata without message still classifies as incoming for legacy behavior`() {
        val receipt = PushReceiptParser.parse(mapOf(
            "metadata" to """{"call_id":"87654321-dcba-4321-dcba-0987654321fe","caller_number":"+15551234"}"""
        ))
        assertEquals(true, receipt is PushReceipt.Incoming)
    }

    @Test
    fun `invalid metadata json without message is unrecognized`() {
        assertTrue(PushReceiptParser.parse(mapOf("metadata" to "not-json")) is PushReceipt.Unrecognized)
    }

    @Test
    fun `null metadata json is unrecognized`() {
        assertTrue(PushReceiptParser.parse(mapOf("metadata" to "null")) is PushReceipt.Unrecognized)
    }

    @Test
    fun `metadata without a valid uuid call id is not treated as invalid`() {
        // The push still shows a notification; only the receipt fact needs the call id.
        val callId = PushReceiptParser.callIdFromMetadata("""{"call_id":"not-a-uuid"}""")
        assertNull(callId)
    }

    @Test
    fun `call id parsing tolerates absent metadata`() {
        assertNull(PushReceiptParser.callIdFromMetadata(null))
        assertNull(PushReceiptParser.callIdFromMetadata(""))
        assertNull(PushReceiptParser.callIdFromMetadata("[]"))
    }
}
