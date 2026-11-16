package com.telnyx.webrtc.sdk.observability

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Local queue drain only; WebSocket acceptance is neither an ACK nor durable delivery. */
internal suspend fun awaitV2TelemetryDrain(timeoutMs: Long, pendingCount: suspend () -> Int): Boolean =
    withTimeoutOrNull(timeoutMs.coerceIn(0L, 1500L)) {
        while (pendingCount() > 0) delay(25L)
        true
    } ?: false
