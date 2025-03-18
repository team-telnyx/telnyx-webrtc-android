package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import java.util.*

/**
 * Class responsible for handling the bye received event.
 *
 */
class OnByeReceived {

    /**
     * Ends the call with the specified call ID.
     *
     * @param callId The unique identifier of the call to end.
     */
    operator fun invoke(context: Context, callId: UUID) {
        val telnyxCommon = TelnyxCommon.getInstance()
        if (telnyxCommon.currentCall?.callId == callId)
            telnyxCommon.setCurrentCall(context, null)

        telnyxCommon.unregisterCall(callId)
    }
}
