package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import java.util.*

/**
 * Class responsible for ending the current call and accepting the incoming call.
 *
 * @param context The application context.
 */
class EndCurrentAndAcceptIncoming(private val context: Context) {

    /**
     * Ends the current call and accepts the incoming call with the specified call ID and destination number.
     *
     * @param callId The call ID to accept.
     * @param callerIdNumber The destination number to accept the call.
     * @param customeHeaders The custom headers to accept the call.
     * @return The accepted incoming call.
     */
    operator fun invoke(callId: UUID, callerIdNumber: String, customeHeaders: Map<String, String>? = null): Call {
        val telnyxCommon = TelnyxCommon.getInstance()
        telnyxCommon.currentCall?.let { currentCall ->
            telnyxCommon.getTelnyxClient(context).endCall(currentCall.callId)
            telnyxCommon.unregisterCall(currentCall.callId)
        }

        return AcceptCall(context).invoke(callId, callerIdNumber, customeHeaders)
    }
}
