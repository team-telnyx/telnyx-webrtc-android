package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.mobile.shared.observability.contract.v2.ClientInfoEnvironment
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Endpoints

/** V2 reporting controls. V1 diagnostics remain available during migration. */
data class CallReportV2Config(
    val enabled: Boolean = true,
    val captureOnly: Boolean = false,
    val mirrorConsole: Boolean = true,
    /** Telemetry environment; follows the connected signaling configuration unless explicitly configured. */
    val development: Boolean = false,
    /** Local protocol testing only; production endpoints come from the canonical shared selector. */
    val localEndpoint: String? = null,
) {
    init {
        localEndpoint?.let {
            val uri = java.net.URI(it)
            require(uri.scheme in listOf("ws", "wss") && uri.host in listOf("127.0.0.1", "localhost", "[::1]")) {
                "Only loopback telemetry endpoint overrides are supported"
            }
        }
    }
}

/** Canonical shared endpoint selection for a V2 configuration; loopback overrides win. */
internal fun callReportV2Endpoint(config: CallReportV2Config): String =
    config.localEndpoint ?: V2Endpoints.forEnvironment(if (config.development) ClientInfoEnvironment.DEVELOPMENT else ClientInfoEnvironment.PRODUCTION)
