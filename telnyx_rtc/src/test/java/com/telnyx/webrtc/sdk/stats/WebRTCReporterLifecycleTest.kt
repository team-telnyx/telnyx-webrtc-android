package com.telnyx.webrtc.sdk.stats

import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.sdk.peer.Peer
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * VSUP-324: the stats timer and the statsDataFlow collector must be tracked
 * by WebRTCReporter so stopStats()/pauseStats() stop them, and each stats
 * event must be collected exactly once.
 */
class WebRTCReporterLifecycleTest : BaseTest() {

    private val socket = mockk<TxSocket>(relaxed = true)
    private val peer = mockk<Peer>(relaxed = true)
    private val timerTicks = AtomicInteger(0)
    private lateinit var reporter: WebRTCReporter

    @Before
    fun setup() {
        every { peer.iceServer } returns emptyList()
        every { peer.iceCandidatePoolSize } returns 0
        // A live peer: count timer iterations without running the native getStats.
        every { peer.withLiveConnection<Any?>(any()) } answers {
            timerTicks.incrementAndGet()
            Unit
        }

        reporter = WebRTCReporter(
            socket = socket,
            peerId = UUID.randomUUID(),
            connectionId = "connection-id",
            peer = peer,
            callDebug = true, // 100 ms stats interval keeps the test fast
            socketDebug = false,
        )
    }

    @After
    fun tearDown() {
        // pauseStats() is safe to repeat; stopStats() is single-shot by design.
        reporter.pauseStats()
    }

    @Test
    fun `stats events have exactly one collector`() {
        reporter.startStats()
        waitUntil { reporter.statsDataFlow.subscriptionCount.value >= 1 }
        // Give a duplicate collector time to subscribe, if there were one.
        Thread.sleep(SETTLE_MS)

        assertEquals(1, reporter.statsDataFlow.subscriptionCount.value)
    }

    @Test
    fun `signaling change event is processed once`() {
        reporter.startStats()
        waitUntil { reporter.statsDataFlow.subscriptionCount.value >= 1 }
        Thread.sleep(SETTLE_MS)

        reporter.onStatsDataEvent(
            StatsData.PeerEvent(WebRTCStatsEvent.SIGNALING_CHANGE, PeerConnection.SignalingState.STABLE)
        )
        Thread.sleep(SETTLE_MS)

        verify(exactly = 1) { peer.getLocalDescription() }
        verify(exactly = 1) { peer.getRemoteDescription() }
    }

    @Test
    fun `stopStats stops the timer and the collector`() {
        reporter.startStats()
        waitUntil { timerTicks.get() >= 2 && reporter.statsDataFlow.subscriptionCount.value >= 1 }

        reporter.stopStats()
        waitUntil { reporter.statsDataFlow.subscriptionCount.value == 0 }
        val ticksAfterStop = timerTicks.get()
        Thread.sleep(SETTLE_MS * 2)

        // At most one iteration already past its cancellation check may complete.
        assertTrue(timerTicks.get() - ticksAfterStop <= 1)
    }

    @Test
    fun `pauseStats stops the timer and the collector`() {
        reporter.startStats()
        waitUntil { timerTicks.get() >= 2 && reporter.statsDataFlow.subscriptionCount.value >= 1 }

        reporter.pauseStats()
        waitUntil { reporter.statsDataFlow.subscriptionCount.value == 0 }
        val ticksAfterPause = timerTicks.get()
        Thread.sleep(SETTLE_MS * 2)

        assertTrue(timerTicks.get() - ticksAfterPause <= 1)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "Condition not met within ${WAIT_TIMEOUT_MS}ms" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val WAIT_TIMEOUT_MS = 3_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 10L
    }
}
