package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.model.AudioCodec
import com.telnyx.webrtc.sdk.model.AudioConstraints
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
     * @param preferredCodecs Optional list of preferred audio codecs for the call.
     * @param audioConstraints Optional audio processing constraints for the call.
     * @param onCallQualityChange Optional callback for receiving real-time call quality metrics.
     * @param onCallHistoryAdd Optional callback for adding call to history.
     * @return The created outgoing call.
     */
    operator fun invoke(
        callerName: String,
        callerNumber: String,
        destinationNumber: String,
        clientState: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        preferredCodecs: List<AudioCodec>? = null,
        audioConstraints: AudioConstraints? = null,
        onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null,
        onCallHistoryAdd: (suspend (String) -> Unit)? = null
    ): Call {
        val telnyxCommon = TelnyxCommon.getInstance()
        val telnyxClient = telnyxCommon.getTelnyxClient(context)

        val outgoingCall = telnyxClient.newInvite(callerName, callerNumber, destinationNumber, clientState, customHeaders, debug, preferredCodecs, audioConstraints)
        
        // Set the call quality change callback if provided
        if (debug && onCallQualityChange != null) {
            outgoingCall.onCallQualityChange = onCallQualityChange
        }
        
        telnyxCommon.setCurrentCall(context, outgoingCall)
        telnyxCommon.registerCall(outgoingCall)
        
        // Add call to history if callback is provided
        onCallHistoryAdd?.let { callback ->
            CoroutineScope(Dispatchers.IO).launch {
                callback(destinationNumber)
            }
        }
        
        return outgoingCall
    }
}
