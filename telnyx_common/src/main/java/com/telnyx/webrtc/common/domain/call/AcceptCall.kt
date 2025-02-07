package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import java.util.*

/**
 * Class responsible for accepting a call.
 *
 * @param context The application context.
 */
class AcceptCall(private val context: Context) {

    /**
     * Accepts a call with the specified call ID and destination number.
     *
     * @param callId The call ID to accept.
     * @param callerIdNumber The destination number to accept the call.
     * @param customeHeaders The custom headers to accept the call.
     */
    operator fun invoke(callId: UUID, callerIdNumber: String, customeHeaders: Map<String, String>? = null): Call {
        val telnyxCommon = TelnyxCommon.getInstance()
        val incomingCall = telnyxCommon.getTelnyxClient(context).acceptCall(callId, callerIdNumber, customeHeaders)
        telnyxCommon.setCurrentCall(incomingCall)
        telnyxCommon.registerCall(incomingCall)
        return incomingCall
    }
}
