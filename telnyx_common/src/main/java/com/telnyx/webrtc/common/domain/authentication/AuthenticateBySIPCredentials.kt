package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import androidx.lifecycle.LiveData
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse

class AuthenticateBySIPCredentials(private val context: Context) {

    operator fun invoke(credentialConfig: CredentialConfig, txPushMetaData: String? = null, autoLogin: Boolean = true): LiveData<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getTelnyxClient(context)

        telnyxClient.connect(TxServerConfiguration(),
            credentialConfig,
            txPushMetaData,
            autoLogin)

        return telnyxClient.getSocketResponse()
    }
}
