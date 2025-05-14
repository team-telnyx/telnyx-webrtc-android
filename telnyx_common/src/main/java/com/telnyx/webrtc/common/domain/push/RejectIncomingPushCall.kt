package com.telnyx.webrtc.common.domain.push

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.domain.call.EndCurrentAndUnholdLast
import com.telnyx.webrtc.common.util.toCredentialConfig
import com.telnyx.webrtc.common.util.toTokenConfig
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import java.util.*

/**
 * Class responsible for handling the rejection of incoming push calls.
 *
 * @property context The context in which the class operates.
 */
class RejectIncomingPushCall(private val context: Context) {

    // Callback to be invoked when the call ends.
    private var onCallEnded: ((UUID) -> Unit)? = null

    // Observer for incoming call responses.
    private val incomingCallObserver = Observer<SocketResponse<ReceivedMessageBody>> { response ->
        handleSocketResponse(response)
    }

    /**
     * Invokes the rejection of an incoming push call.
     *
     * @param txPushMetaData Metadata associated with the push notification.
     * @param onCallEnded Callback to be invoked when the call ends.
     * @return LiveData containing the socket response.
     */
    operator fun invoke(
        txPushMetaData: String?,
        onCallEnded: (UUID) -> Unit
    ): LiveData<SocketResponse<ReceivedMessageBody>> {
        this.onCallEnded = onCallEnded

        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)
        ProfileManager.getProfilesList(context).lastOrNull()?.let { lastProfile ->
            val fcmToken = lastProfile.fcmToken ?: ""

            // Use TokenConfig when sipToken is not null, otherwise use CredentialConfig
            if (lastProfile.sipToken != null) {
                telnyxClient.connect(
                    TxServerConfiguration(),
                    lastProfile.toTokenConfig(fcmToken),
                    txPushMetaData,
                    true
                )
            } else {
                telnyxClient.connect(
                    TxServerConfiguration(),
                    lastProfile.toCredentialConfig(fcmToken),
                    txPushMetaData,
                    true
                )
            }

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
                    EndCurrentAndUnholdLast(context).invoke(inviteResponse.callId)
                    cleanUp(inviteResponse.callId)
                }
            }
        }
    }

    /**
     * Cleans up resources and removes the observer after the call ends.
     *
     * @param callId The UUID of the call that ended.
     */
    private fun cleanUp(callId: UUID) {
        TelnyxCommon.getInstance().getTelnyxClient(context).getSocketResponse().removeObserver(incomingCallObserver)
        onCallEnded?.invoke(callId)
    }
}
