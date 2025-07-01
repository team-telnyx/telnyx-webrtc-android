package com.telnyx.webrtc.common.util

import android.media.RingtoneManager
import com.telnyx.webrtc.common.R
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.LogLevel

fun Profile.toCredentialConfig(fcmToken: String, isDebug: Boolean = false): CredentialConfig {
    return CredentialConfig(
        sipUser = sipUsername ?: "",
        sipPassword = sipPass ?: "",
        sipCallerIDName = this.callerIdName,
        sipCallerIDNumber = callerIdNumber,
        logLevel = LogLevel.ALL,
        debug = isDebug,
        fcmToken = fcmToken,
        ringtone =  RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ringBackTone = R.raw.ringback_tone,
        autoReconnect = true,
        region = this.region,
        forceRelayCandidate = this.forceRelayCandidate
    )
}


fun Profile.toTokenConfig(fcmToken: String, isDebug: Boolean = false): TokenConfig {
    return TokenConfig(
        sipToken= sipToken ?: "",
        sipCallerIDName = this.callerIdName,
        sipCallerIDNumber = callerIdNumber,
        logLevel = LogLevel.ALL,
        debug = isDebug,
        fcmToken = fcmToken,
        ringtone =  RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ringBackTone = R.raw.ringback_tone,
        autoReconnect = true,
        region = this.region,
        forceRelayCandidate = this.forceRelayCandidate
    )
}
