package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call

/**
 * Class responsible for holding or unholding a call.
 *
 * @param context The application context.
 */
class HoldUnholdCall(private val context: Context) {

    /**
     * Holds or unholds the call with the specified call ID.
     *
     * @param call The call to hold or unhold.
     */
    operator fun invoke(call: Call) {
        TelnyxCommon.getInstance().getTelnyxClient(context).getActiveCalls()[call.callId]?.let { currentCall ->
            currentCall.onHoldUnholdPressed(currentCall.callId)
        }

    }
}
