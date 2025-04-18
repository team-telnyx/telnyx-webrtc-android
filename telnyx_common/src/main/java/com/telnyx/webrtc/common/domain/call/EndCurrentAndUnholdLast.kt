package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import java.util.*

/**
 * Class responsible for ending the current call and unholding the last holded call (if exists).
 *
 * @param context The application context.
 */
class EndCurrentAndUnholdLast(private val context: Context) {

    /**
     * Ends the call with the specified call ID.
     *
     * @param callId The unique identifier of the call to end.
     */
    operator fun invoke(callId: UUID) {
        val telnyxCommon = TelnyxCommon.getInstance()
        telnyxCommon.getTelnyxClient(context).endCall(callId)
        telnyxCommon.unregisterCall(callId)

        telnyxCommon.heldCalls.value.lastOrNull()?.let { lastHoldedCall ->
            telnyxCommon.setCurrentCall(context, lastHoldedCall)
            HoldUnholdCall(context).invoke(lastHoldedCall)
        }

    }
}
