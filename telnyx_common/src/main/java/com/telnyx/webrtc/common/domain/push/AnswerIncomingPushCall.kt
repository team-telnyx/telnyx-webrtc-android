package com.telnyx.webrtc.common.domain.push

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.domain.call.AcceptCall
import com.telnyx.webrtc.common.util.toCredentialConfig
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse

/**
 * Class responsible for handling the acceptance of incoming push calls.
 *
 * @property context The context in which the class operates.
 */
class AnswerIncomingPushCall(private val context: Context) {
    // Custom headers to be included in the call.
    private var customHeaders: Map<String, String>? = null

    // Callback to be invoked when the call is answered.
    private var onCallAnswered: ((Call) -> Unit)? = null

    // Observer for incoming call responses.
    private val incomingCallObserver = Observer<SocketResponse<ReceivedMessageBody>> { response ->
        handleSocketResponse(response)
    }

    /**
     * Invokes the acceptance of an incoming push call.
     *
     * @param txPushMetaData Metadata associated with the push notification.
     * @param customHeaders Custom headers to be included in the call.
     * @param onCallAnswered Callback to be invoked when the call is answered.
     * @return LiveData containing the socket response.
     */
    operator fun invoke(
        txPushMetaData: String?,
        customHeaders: Map<String, String>? = null,
        onCallAnswered: (Call) -> Unit
    ): LiveData<SocketResponse<ReceivedMessageBody>> {
        this.customHeaders = customHeaders
        this.onCallAnswered = onCallAnswered

        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)
        ProfileManager.getProfilesList(context).lastOrNull()?.let { lastProfile ->
            telnyxClient.connect(
                TxServerConfiguration(),
                lastProfile.toCredentialConfig(lastProfile.fcmToken ?: ""),
                txPushMetaData,
                true
            )
            telnyxClient.getSocketResponse().observeForever(incomingCallObserver)
        }

        return telnyxClient.getSocketResponse()
    }

    /**
     * Handles the socket response for incoming calls.
     *
     * @param response The socket response containing the received message body.
     */
    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        if (response.status == SocketStatus.MESSAGERECEIVED) {
            if (response.data?.method == SocketMethod.INVITE.methodName) {
                (response.data?.result as? InviteResponse)?.let { inviteResponse ->
                    Thread.sleep(Call.ICE_CANDIDATE_DELAY) // Minor delay to ensure ICE candidates are gathered.
                    val answeredCall = AcceptCall(context).invoke(
                        inviteResponse.callId,
                        inviteResponse.callerIdNumber,
                        customHeaders
                    )
                    cleanUp(answeredCall)
                }
            }
        }
    }

    /**
     * Cleans up resources and removes the observer after the call is answered.
     *
     * @param call The call that was answered.
     */
    private fun cleanUp(call: Call) {
        TelnyxCommon.getInstance().getTelnyxClient(context).getSocketResponse().removeObserver(incomingCallObserver)
        onCallAnswered?.invoke(call)
    }
}
