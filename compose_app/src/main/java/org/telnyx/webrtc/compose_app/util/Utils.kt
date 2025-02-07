package org.telnyx.webrtc.compose_app.util

import android.media.RingtoneManager
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.LogLevel
import org.telnyx.webrtc.compose_app.R

fun Profile.toCredentialConfig(fcmToken: String): CredentialConfig {
    return CredentialConfig(
        sipUser = sipUsername ?: "",
        sipPassword = sipPass ?: "",
        sipCallerIDName = this.callerIdName,
        sipCallerIDNumber = callerIdNumber,
        logLevel = LogLevel.ALL,
        debug = true,
        fcmToken = fcmToken,
        ringtone =  RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ringBackTone = R.raw.ringback_tone,
        autoReconnect = true
    )
}


fun Profile.toTokenConfig(fcmToken: String): TokenConfig {
    return TokenConfig(
        sipToken= sipToken ?: "",
        sipCallerIDName = this.callerIdName,
        sipCallerIDNumber = callerIdNumber,
        logLevel = LogLevel.ALL,
        debug = true,
        fcmToken = fcmToken,
        ringtone =  RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ringBackTone = R.raw.ringback_tone,
        autoReconnect = true
    )
}