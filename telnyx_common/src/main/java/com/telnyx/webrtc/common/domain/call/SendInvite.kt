package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon

/**
 * Class responsible for sending an invite.
 *
 * @param context The application context.
 */
class SendInvite(private val context: Context) {

    /**
     * Sends an invite to the specified destination number.
     *
     * @param callerName The name of the caller.
     * @param callerNumber The number of the caller.
     * @param destinationNumber The destination number to send the invite.
     * @param clientState The client state to send the invite.
     * @param customeHeaders The custom headers to send the invite.
     */
    operator fun invoke(callerName: String,
                        callerNumber: String,
                        destinationNumber: String,
                        clientState: String,
                        customeHeaders: Map<String, String>? = null) {
        val telnyxCommon = TelnyxCommon.getInstance()
        val telnyxClient = telnyxCommon.getTelnyxClient(context)

        val outgoingCall = telnyxClient.newInvite(callerName, callerNumber, destinationNumber, clientState, customeHeaders)
        telnyxCommon.setCurrentCall(outgoingCall)
        telnyxCommon.registerCall(outgoingCall)
    }
}
