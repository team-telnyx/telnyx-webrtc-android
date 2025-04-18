package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics

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
     * @param customHeaders The custom headers to send the invite.
     * @param debug When true, enables real-time call quality metrics.
     * @param onCallQualityChange Optional callback for receiving real-time call quality metrics.
     * @return The created outgoing call.
     */
    operator fun invoke(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null
    ): Call {
        val telnyxCommon = TelnyxCommon.getInstance()
        val telnyxClient = telnyxCommon.getTelnyxClient(context)

        val outgoingCall = telnyxClient.newInvite(callerName, callerNumber, destinationNumber, clientState, customHeaders, debug)
        
        // Set the call quality change callback if provided
        if (debug && onCallQualityChange != null) {
            outgoingCall.onCallQualityChange = onCallQualityChange
        }
        
        telnyxCommon.setCurrentCall(context, outgoingCall)
        telnyxCommon.registerCall(outgoingCall)
        
        return outgoingCall
    }
}
