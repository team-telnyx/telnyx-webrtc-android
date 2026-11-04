package com.telnyx.webrtc.sdk.observability

/** Effective only when the reviewed V2 shared integration is linked. Ordinary artifacts remain V1-only. */
data class CallReportV2Config(
    val enabled: Boolean = true,
    val captureOnly: Boolean = false,
    val mirrorConsole: Boolean = true,
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
