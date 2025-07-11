package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import androidx.lifecycle.LiveData
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.SharedFlow

/**
 * This class handles the authentication process using SIP credentials.
 *
 * @param context The context used to access application-specific resources.
 */
class AuthenticateBySIPCredentials(private val context: Context) {

    /**
     * Authenticates using SIP credentials and returns a SharedFlow (recommended)
     *
     * @param serverConfig The configuration for the server.
     * @param credentialConfig The configuration for the SIP credentials.
     * @param txPushMetaData Metadata associated with the push notification.
     * @param autoLogin Whether to automatically log in the user.
     *
     * @return A SharedFlow emitting the socket response.
     */
    fun invokeFlow(
        serverConfig: TxServerConfiguration = TxServerConfiguration(),
        credentialConfig: CredentialConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true
    ): SharedFlow<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        telnyxClient.connect(
            serverConfig,
            credentialConfig,
            txPushMetaData,
            autoLogin
        )

        ProfileManager.saveProfile(
            context, Profile(
                sipUsername = credentialConfig.sipUser,
                sipPass = credentialConfig.sipPassword,
                callerIdName = credentialConfig.sipCallerIDName,
                callerIdNumber = credentialConfig.sipCallerIDNumber,
                isUserLoggedIn = true,
                fcmToken = credentialConfig.fcmToken,
                region = credentialConfig.region,
                forceRelayCandidate = credentialConfig.forceRelayCandidate
            )
        )

        return telnyxClient.socketResponseFlow
    }

    /**
     * Authenticates using SIP credentials and returns LiveData (deprecated)
     * @deprecated Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    operator fun invoke(
        serverConfig: TxServerConfiguration = TxServerConfiguration(),
        credentialConfig: CredentialConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true
    ): LiveData<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        telnyxClient.connect(
            serverConfig,
            credentialConfig,
            txPushMetaData,
            autoLogin
        )

        ProfileManager.saveProfile(
            context, Profile(
                sipUsername = credentialConfig.sipUser,
                sipPass = credentialConfig.sipPassword,
                callerIdName = credentialConfig.sipCallerIDName,
                callerIdNumber = credentialConfig.sipCallerIDNumber,
                isUserLoggedIn = true,
                fcmToken = credentialConfig.fcmToken,
                region = credentialConfig.region,
                forceRelayCandidate = credentialConfig.forceRelayCandidate
            )
        )

        return telnyxClient.getSocketResponse()
    }
}
