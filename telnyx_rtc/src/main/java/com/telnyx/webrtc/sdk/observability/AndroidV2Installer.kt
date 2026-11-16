package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient

/** Installs the reporting owner for a client and routes environment/endpoint-only reconfiguration
 * through the ordered retarget hook, preserving the owner, queue and leases. */
@androidx.annotation.Keep
object AndroidV2Installer {
    private data class Installation(val config: CallReportV2Config,
        val sink: java.lang.ref.WeakReference<CallReportV2Ingress>?,
        val revision: Long, val pendingRetarget: Boolean)
    private val installations = java.util.WeakHashMap<TelnyxClient, Installation>()

    @JvmStatic fun install(client: TelnyxClient, config: CallReportV2Config, constructing: Boolean = false,
        explicit: Boolean = false) {
        synchronized(client) {
            if (explicit) client.callReportV2ExplicitlyConfigured = true
            val current = synchronized(installations) { installations[client] }
            val revision = (current?.revision ?: 0L) + 1L
            val owner = client.callReportV2
            val installed = current
            if (installed == null || installed.sink?.get() !== owner || owner?.isActive() != true) {
                rebuild(client, current, config, constructing, revision)
                return
            }
            if (installed.config.enabled != config.enabled || installed.config.captureOnly != config.captureOnly) {
                // Delivery-authority changes are an explicit lifecycle: dispose and rebuild.
                // Disabling must stay honored; a later enable reports future observations only.
                rebuild(client, current, config, constructing, revision)
                return
            }
            val envChanged = installed.config.development != config.development ||
                installed.config.localEndpoint != config.localEndpoint
            val mirrorChanged = installed.config.mirrorConsole != config.mirrorConsole
            if (envChanged && mirrorChanged) {
                throw UnsupportedOperationException(
                    "Call Report V2 cannot combine environment/endpoint and mirroring changes on the live " +
                        "reporting owner without destroying queued startup records; owner and records are preserved")
            }
            if (envChanged && !NATIVE_V2_RETARGET_SUPPORTED) {
                throw UnsupportedOperationException(
                    "Call Report V2 environment or endpoint changes require the candidate observability runtime " +
                        "(retargetBeforeFirstCredential); the reporting owner and its records are preserved and the request was not applied")
            }
            if (mirrorChanged) {
                throw UnsupportedOperationException(
                    "Call Report V2 mirroring changes cannot replace the live reporting owner without destroying " +
                        "queued startup records; the owner and its records are preserved and the request was not applied")
            }
            if (!envChanged && !installed.pendingRetarget) {
                synchronized(installations) { installations[client] = installed.copy(revision = revision) }
                return
            }
            val capturedRevision = revision
            synchronized(installations) { installations[client] = installed.copy(revision = revision, pendingRetarget = true) }
            owner.retarget(config,
                guard = { revisionIs(client, capturedRevision) },
                onApplied = { appliedRetarget(client, config, capturedRevision) },
                onRejected = { failedRetarget(client, capturedRevision, it, explicit) })
        }
    }

    private fun rebuild(client: TelnyxClient, current: Installation?, config: CallReportV2Config,
        constructing: Boolean, revision: Long) {
        client.callReportV2?.dispose()
        client.callReportV2 = null
        if (config.enabled && !client.callReportV2DisabledByServer.get()) {
            AndroidV2Capture(client, config, constructing, suppressCreationEvents = current != null)
        }
        synchronized(installations) {
            installations[client] = Installation(config, client.callReportV2?.let { java.lang.ref.WeakReference(it) }, revision, pendingRetarget = false)
        }
    }

    @JvmStatic internal fun installedConfigFor(client: TelnyxClient): CallReportV2Config? =
        synchronized(installations) { installations[client]?.config }

    private fun revisionIs(client: TelnyxClient, capturedRevision: Long): Boolean =
        synchronized(installations) { installations[client]?.revision } == capturedRevision

    private fun appliedRetarget(client: TelnyxClient, requested: CallReportV2Config, capturedRevision: Long) {
        synchronized(installations) {
            val current = installations[client] ?: return
            if (current.revision != capturedRevision) return
            installations[client] = current.copy(config = requested, pendingRetarget = false)
        }
    }

    private fun failedRetarget(client: TelnyxClient, capturedRevision: Long, failure: Throwable, explicit: Boolean) {
        synchronized(installations) {
            installations[client]?.let { installations[client] = it.copy(pendingRetarget = false) }
        }
        val superseded = failure is IllegalStateException && failure.message?.contains("superseded") == true
        val message = "Call Report V2 ${if (explicit) "explicit" else "auto"} retarget request not applied: $failure"
        if (superseded) {
            com.telnyx.webrtc.sdk.utilities.Logger.d(tag = "CallReportV2", message = message)
        } else {
            com.telnyx.webrtc.sdk.utilities.Logger.w(tag = "CallReportV2", message = message)
        }
    }
}
