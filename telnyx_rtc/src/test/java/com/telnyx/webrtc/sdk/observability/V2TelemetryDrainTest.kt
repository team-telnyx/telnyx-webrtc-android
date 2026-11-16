@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.telnyx.webrtc.sdk.observability

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class V2TelemetryDrainTest {
    @Test fun `pending telemetry may drain without blocking the caller thread`() = runTest(StandardTestDispatcher(TestCoroutineScheduler())) {
        var pending = 2
        launch { delay(60); pending = 0 }
        assertTrue(awaitV2TelemetryDrain(500) { pending })
        assertEquals(75L, testScheduler.currentTime)
    }

    @Test fun `drain timeout is capped even when pending snapshot suspends`() = runTest(StandardTestDispatcher(TestCoroutineScheduler())) {
        assertFalse(awaitV2TelemetryDrain(30_000) { delay(10_000); 1 })
        assertEquals(1500L, testScheduler.currentTime)
        assertFalse(awaitV2TelemetryDrain(0) { 0 })
    }

    @Test fun `cancelled cleanup does not wait out the drain budget`() = runTest(StandardTestDispatcher(TestCoroutineScheduler())) {
        val entered = CompletableDeferred<Unit>()
        val task = async { awaitV2TelemetryDrain(1500) { entered.complete(Unit); 1 } }
        entered.await(); runCurrent()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertEquals(0L, testScheduler.currentTime)
    }
}
