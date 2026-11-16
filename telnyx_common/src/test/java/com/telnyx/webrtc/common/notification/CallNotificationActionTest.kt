package com.telnyx.webrtc.common.notification

import com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/**
 * The receiver used to drop every End action from telemetry because the
 * generic CANCEL branch had no call identity, even though the ongoing-call
 * End intent carries the call metadata. END was declared on the push event
 * but never emitted anywhere.
 */
class CallNotificationActionTest {

    private val callId: UUID = UUID.randomUUID()

    @Test
    fun `answer notification state maps to answer action`() {
        assertEquals(CallReportV2PushEvent.Action.ANSWER,
            CallNotificationReceiver.actionFor(CallNotificationService.Companion.NotificationState.ANSWER.ordinal, callId))
    }

    @Test
    fun `reject notification state maps to reject action`() {
        assertEquals(CallReportV2PushEvent.Action.REJECT,
            CallNotificationReceiver.actionFor(CallNotificationService.Companion.NotificationState.REJECT.ordinal, callId))
    }

    @Test
    fun `cancel notification state with a call id maps to end action`() {
        assertEquals(CallReportV2PushEvent.Action.END,
            CallNotificationReceiver.actionFor(CallNotificationService.Companion.NotificationState.CANCEL.ordinal, callId))
    }

    @Test
    fun `cancel notification state without a call id has no action`() {
        assertNull(CallNotificationReceiver.actionFor(CallNotificationService.Companion.NotificationState.CANCEL.ordinal, null))
    }

    @Test
    fun `unknown notification state has no action`() {
        assertNull(CallNotificationReceiver.actionFor(Int.MAX_VALUE, callId))
    }
}
