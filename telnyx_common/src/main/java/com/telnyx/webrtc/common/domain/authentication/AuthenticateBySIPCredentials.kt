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

/**
 * This class handles the authentication process using SIP credentials.
 *
 * @param context The context used to access application-specific resources.
 */
class AuthenticateBySIPCredentials(private val context: Context) {

    operator fun invoke(credentialConfig: CredentialConfig, txPushMetaData: String? = null, autoLogin: Boolean = true): LiveData<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        telnyxClient.connect(TxServerConfiguration(),
            credentialConfig,
            txPushMetaData,
            autoLogin)

        ProfileManager.saveProfile(context,Profile(sipUsername = credentialConfig.sipUser,
            sipPass = credentialConfig.sipPassword,
            callerIdName = credentialConfig.sipCallerIDName,
            callerIdNumber = credentialConfig.sipCallerIDNumber))

        return telnyxClient.getSocketResponse()
    }
}
