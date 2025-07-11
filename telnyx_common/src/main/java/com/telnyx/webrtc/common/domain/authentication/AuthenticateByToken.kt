package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import androidx.lifecycle.LiveData
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.SharedFlow

/**
 * This class handles the authentication process using a token.
 *
 * @param context The context used to access application-specific resources.
 */
class AuthenticateByToken(private val context: Context) {

    /**
     * Authenticates using token and returns a SharedFlow (recommended)
     *
     * @param serverConfig The configuration for the server.
     * @param tokenConfig The configuration for the token.
     * @param txPushMetaData Metadata associated with the push notification.
     * @param autoLogin Whether to automatically log in the user.
     *
     * @return A SharedFlow emitting the socket response.
     */
    fun invokeFlow(
        serverConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true
    ): SharedFlow<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        telnyxClient.connect(
            serverConfig,
            tokenConfig,
            txPushMetaData,
            autoLogin
        )

        ProfileManager.saveProfile(
            context, Profile(
                sipToken = tokenConfig.sipToken,
                callerIdName = tokenConfig.sipCallerIDName,
                callerIdNumber = tokenConfig.sipCallerIDNumber,
                isUserLoggedIn = true,
                fcmToken = tokenConfig.fcmToken,
                forceRelayCandidate = tokenConfig.forceRelayCandidate
            )
        )

        return telnyxClient.socketResponseFlow
    }

    /**
     * Authenticates using token and returns LiveData (deprecated)
     * @deprecated Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.
     */
    @Deprecated("Use invokeFlow() instead. LiveData is deprecated in favor of Kotlin Flows.")
    operator fun invoke(
        serverConfig: TxServerConfiguration = TxServerConfiguration(),
        tokenConfig: TokenConfig,
        txPushMetaData: String? = null,
        autoLogin: Boolean = true
    ): LiveData<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        telnyxClient.connect(
            serverConfig,
            tokenConfig,
            txPushMetaData,
            autoLogin
        )

        ProfileManager.saveProfile(
            context, Profile(
                sipToken = tokenConfig.sipToken,
                callerIdName = tokenConfig.sipCallerIDName,
                callerIdNumber = tokenConfig.sipCallerIDNumber,
                isUserLoggedIn = true,
                fcmToken = tokenConfig.fcmToken,
                forceRelayCandidate = tokenConfig.forceRelayCandidate
            )
        )

        return telnyxClient.getSocketResponse()
    }
}
