package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import java.util.*

/**
 * Class responsible for holding the current call and accepting an incoming call.
 *
 * @param context The application context.
 */
class HoldCurrentAndAcceptIncoming(private val context: Context) {

    /**
     * Holds the current call and accepts the incoming call with the specified call ID and destination number.
     *
     * @param callId The call ID to accept.
     * @param callerIdNumber The destination number to accept the call.
     * @param customeHeaders The custom headers to accept the call.
     * @return The accepted incoming call.
     */
    operator fun invoke(
        callId: UUID,
        callerIdNumber: String,
        customeHeaders: Map<String, String>? = null,
        debug: Boolean
    ): Call {
        val telnyxCommon = TelnyxCommon.getInstance()

        telnyxCommon.currentCall?.let { currentCall ->
            if (currentCall.getIsOnHoldStatus().value == false) {
                currentCall.onHoldUnholdPressed(currentCall.callId)
            }
        }

        return AcceptCall(context).invoke(
            callId,
            callerIdNumber,
            customeHeaders,
            debug,
            audioConstraints = null  // Use defaults when accepting while holding
        )
    }
}
