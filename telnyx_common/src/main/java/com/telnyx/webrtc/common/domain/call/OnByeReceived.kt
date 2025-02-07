package com.telnyx.webrtc.common.domain.call

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
    operator fun invoke(callId: UUID) {
        val telnyxCommon = TelnyxCommon.getInstance()
        if (telnyxCommon.currentCall?.callId == callId)
            telnyxCommon.setCurrentCall(null)

        telnyxCommon.unregisterCall(callId)
    }
}
