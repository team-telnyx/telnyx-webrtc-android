package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.model.CauseCode
import java.util.*

/**
 * Class responsible for rejecting the incoming call.
 *
 * @param context The application context.
 */
class RejectCall(private val context: Context) {

    /**
     * Rejects the incoming call with the specified call ID.
     *
     * @param callId The call ID to reject.
     */
    operator fun invoke(callId: UUID) {
        val telnyxCommon = TelnyxCommon.getInstance()
        telnyxCommon.currentCall?.let { currentCall ->
            // Reject incoming call with USER_BUSY cause code
            telnyxCommon.getTelnyxClient(context).endCall(currentCall.callId, CauseCode.USER_BUSY)
            telnyxCommon.unregisterCall(currentCall.callId)
        } ?: run {
            // There is no active call, reject the call by ID with USER_BUSY cause code
            telnyxCommon.getTelnyxClient(context).endCall(callId, CauseCode.USER_BUSY)
            telnyxCommon.unregisterCall(callId)
        }
    }
}
