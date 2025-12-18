package com.telnyx.webrtc.common.domain.push

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.domain.call.AcceptCall
import com.telnyx.webrtc.common.util.toCredentialConfig
import com.telnyx.webrtc.common.util.toTokenConfig
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first

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

    // Indicates whether debug mode is enabled for the call.
    private var debug: Boolean = false

    // Callback to be invoked when there is a change in call quality metrics.
    private var onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null

    // Device token to be included when answering a push notification call.
    private var answeredDeviceToken: String? = null

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
     * @return SharedFlow containing the socket response.
     */
    suspend fun invokeFlow(
        txPushMetaData: String?,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null,
        onCallAnswered: (Call) -> Unit
    ): SharedFlow<SocketResponse<ReceivedMessageBody>> {
        this.customHeaders = customHeaders
        this.onCallAnswered = onCallAnswered
        this.debug = debug
        this.onCallQualityChange = onCallQualityChange

        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)
        val socketFlow = telnyxClient.socketResponseFlow

        ProfileManager.getProfilesList(context).lastOrNull()?.let { lastProfile ->
            val fcmToken = lastProfile.fcmToken ?: ""
            this.answeredDeviceToken = fcmToken.ifEmpty { null }

            // Use TokenConfig when sipToken is not null, otherwise use CredentialConfig
            if (!lastProfile.sipToken.isNullOrEmpty()) {
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

            //answer incoming push call and redirect socket data to ViewModel
            socketFlow.first { response ->
                handleSocketResponse(response)
            }
        }

        return socketFlow
    }

    /**
     * Invokes the acceptance of an incoming push call.
     *
     * @param txPushMetaData Metadata associated with the push notification.
     * @param customHeaders Custom headers to be included in the call.
     * @param onCallAnswered Callback to be invoked when the call is answered.
     * @return LiveData containing the socket response.
     * @deprecated Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    operator fun invoke(
        txPushMetaData: String?,
        customHeaders: Map<String, String>? = null,
        debug: Boolean = false,
        onCallQualityChange: ((CallQualityMetrics) -> Unit)? = null,
        onCallAnswered: (Call) -> Unit
    ): LiveData<SocketResponse<ReceivedMessageBody>> {
        this.customHeaders = customHeaders
        this.onCallAnswered = onCallAnswered
        this.debug = debug
        this.onCallQualityChange = onCallQualityChange

        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)
        ProfileManager.getProfilesList(context).lastOrNull()?.let { lastProfile ->
            val fcmToken = lastProfile.fcmToken ?: ""
            this.answeredDeviceToken = fcmToken.ifEmpty { null }

            // Use TokenConfig when sipToken is not null, otherwise use CredentialConfig
            if (!lastProfile.sipToken.isNullOrEmpty()) {
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
    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>): Boolean {
        if (response.status == SocketStatus.MESSAGERECEIVED) {
            if (response.data?.method == SocketMethod.INVITE.methodName) {
                (response.data?.result as? InviteResponse)?.let { inviteResponse ->
                    val answeredCall = AcceptCall(context).invoke(
                        inviteResponse.callId,
                        inviteResponse.callerIdNumber,
                        customHeaders,
                        debug,
                        audioConstraints = null,  // Use defaults for push calls
                        onCallQualityChange = onCallQualityChange,
                        answeredDeviceToken = answeredDeviceToken
                    )
                    cleanUp(answeredCall)
                    return true
                }
            }
        }

        return false
    }

    /**
     * Cleans up resources and removes the observer after the call is answered.
     *
     * @param call The call that was answered.
     */
    private fun cleanUp(call: Call) {
        TelnyxCommon.getInstance().getTelnyxClient(context).getSocketResponse()
            .removeObserver(incomingCallObserver)
        onCallAnswered?.invoke(call)
    }
}
