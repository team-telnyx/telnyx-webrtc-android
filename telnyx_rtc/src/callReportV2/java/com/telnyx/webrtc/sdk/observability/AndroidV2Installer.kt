package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient

/** Reflective optional-source boundary: ordinary artifacts never link an unpublished dependency. */
@androidx.annotation.Keep
object AndroidV2Installer {
    private data class Installation(val config: CallReportV2Config,
        val sink: java.lang.ref.WeakReference<CallReportV2Ingress>?)
    private val installations = java.util.WeakHashMap<TelnyxClient, Installation>()
    @JvmStatic fun install(client: TelnyxClient, config: CallReportV2Config) {
        synchronized(client) {
            val existing = synchronized(installations) { installations[client] }
            if (existing?.config == config && existing.sink?.get() === client.callReportV2 && client.callReportV2?.isActive() != false) return
            client.callReportV2?.dispose()
            client.callReportV2 = null
            if (config.enabled) AndroidV2Capture(client, config)
            synchronized(installations) {
                installations[client] = Installation(config, client.callReportV2?.let { java.lang.ref.WeakReference(it) })
            }
        }
    }
}
