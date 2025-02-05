package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon

class Disconnect(private val context: Context) {

    operator fun invoke() {
        TelnyxCommon.getTelnyxClient(context).onDisconnect()
        ProfileManager.getLoggedProfile(context)?.let { loggedProfile ->
            val disconnectedProfile = loggedProfile.copy(isUserLogin = false)
            ProfileManager.saveProfile(context, disconnectedProfile)
        }
    }
}