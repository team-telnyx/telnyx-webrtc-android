package com.telnyx.webrtc.common.domain.call

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
     * @param customHeaders The custom headers to accept the call.
     * @param debug When true, enables real-time call quality metrics.
     * @param onCallQualityChange Optional callback for receiving real-time call quality metrics.
     * @param onCallHistoryAdd Optional callback for adding call to history.
     * @return The accepted incoming call.
     */
    operator fun invoke(
        callId: UUID,
        callerIdNumber: String,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null,
        onCallHistoryAdd: (suspend (String) -> Unit)? = null
    ): Call {
        val telnyxCommon = TelnyxCommon.getInstance()
        val incomingCall = telnyxCommon.getTelnyxClient(context).acceptCall(callId, callerIdNumber, customHeaders, debug)
        
        // Set the call quality change callback if provided
        if (debug && onCallQualityChange != null) {
            incomingCall.onCallQualityChange = onCallQualityChange
        }
        
        telnyxCommon.setCurrentCall(context, incomingCall)
        telnyxCommon.registerCall(incomingCall)
        
        // Add call to history if callback is provided
        onCallHistoryAdd?.let { callback ->
            CoroutineScope(Dispatchers.IO).launch {
                callback(callerIdNumber)
            }
        }
        
        return incomingCall
    }
}
