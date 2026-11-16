package com.telnyx.webrtc.sdk.observability

import android.os.Build
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.mobile.shared.observability.contract.v2.*
import com.telnyx.webrtc.mobile.shared.observability.core.v2.*
import kotlinx.coroutines.CoroutineScope

internal fun createNativeV2Runtime(scope: CoroutineScope, client: TelnyxClient,
    config: CallReportV2Config, clock: V2Clock, onDisabled: () -> Unit): V2Runtime {
    return V2Runtime(scope, client.callReportV2InstanceId,
        ClientInfo(if (config.development) ClientInfoEnvironment.DEVELOPMENT else ClientInfoEnvironment.PRODUCTION, ClientInfoSdk.ANDROID, BuildConfig.SDK_VERSION,
            ClientInfoOs.ANDROID, Build.VERSION.RELEASE, "Android-" + BuildConfig.SDK_VERSION),
        LoginType.SIP_CREDENTIALS, callReportV2Endpoint(config), clock,
        com.telnyx.webrtc.mobile.shared.observability.transport.ws.OkHttpV2SocketFactory(),
        captureOnly = config.captureOnly, enabled = config.enabled,
        onFrame = if (config.mirrorConsole) ({ frame -> println(frame) }) else null,
        sequenceProvider = client::nextCallReportV2Sequence)
}

internal const val NATIVE_V2_SERVER_GATE_SUPPORTED = false

internal const val NATIVE_V2_RETARGET_SUPPORTED = false

internal suspend fun retargetV2Runtime(runtime: V2Runtime, config: CallReportV2Config) {
    throw UnsupportedOperationException(
        "retargetBeforeFirstCredential is unavailable in the published observability runtime 0.2.3; " +
            "Call Report V2 environment or endpoint changes require explicit reconfiguration capability " +
            "or a runtime upgrade. The reporting owner is preserved and must not be recreated by this request.")
}
