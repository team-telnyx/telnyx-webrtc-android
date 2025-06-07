package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import com.telnyx.webrtc.common.ProfileManager
import com.telnyx.webrtc.common.TelnyxCommon

/**
 * Class responsible for handling disconnection.
 *
 * @param context The application context.
 */
class Disconnect(private val context: Context) {

    /**
     * Disconnects the current session and updates the profile status.
     */
    operator fun invoke() {
        TelnyxCommon.getInstance().getTelnyxClient(context).disconnect()

        // Reset Telnyx Client
        TelnyxCommon.getInstance().resetTelnyxClient()
    }
}
