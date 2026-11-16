package com.telnyx.webrtc.sdk.observability

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Exactly once, bounded final snapshot. Late callbacks never touch a disposed native peer. */
internal class FinalStatsCapture<T>(
    private val scheduleTimeout: (() -> Unit) -> (() -> Unit),
) {
    fun finish(request: ((T) -> Unit) -> Unit, capture: (T?) -> Unit, dispose: () -> Unit) {
        val finished = AtomicBoolean(false)
        val cancelTimeout = AtomicReference<(() -> Unit)?>(null)
        fun complete(value: T?) {
            if (!finished.compareAndSet(false, true)) return
            try { cancelTimeout.getAndSet(null)?.invoke() } catch (_: Exception) { }
            try { capture(value) } finally { dispose() }
        }
        try { cancelTimeout.set(scheduleTimeout { complete(null) }) }
        catch (_: Exception) { complete(null); return }
        if (finished.get()) { cancelTimeout.getAndSet(null)?.invoke(); return }
        try { request { complete(it) } } catch (_: Exception) { complete(null) }
    }
}
