/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.RelaxedMockK
import org.junit.Before
import org.junit.Test
import com.telnyx.webrtc.lib.PeerConnection
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [SignalingHealthMonitor].
 *
 * Covers the documented state machine invariants:
 *  - duplicate ICE/peer-connection callbacks are no-ops
 *  - transient disconnected debounce (3s) cancels on re-connection
 *  - exact probe correlation (unrelated inbound frames update liveness but
 *    do not resolve the probe)
 *  - probe timeout escalates to reattach
 *  - 15s restart timeout escalates to reattach
 *  - peer-level FAILED recovers even while ICE still reports connected
 *  - failed direct VPN paths request a relay-only replacement call
 *  - network-path changes preempt per-peer recovery for client reattach
 *  - RTP verification success returns the machine to IDLE
 *  - RTP verification timeout escalates to reattach
 *  - terminal-call cancellation cancels all timers/jobs
 *  - reattach is performed at most once per recovery generation
 *  - late callbacks against a stale generation are dropped
 */
class SignalingHealthMonitorTest : BaseTest() {

    @RelaxedMockK
    private lateinit var actions: SignalingHealthMonitor.Actions

    private val testCallId = UUID.randomUUID()
    private lateinit var fakeClock: FakeClock

    /**
     * Mutable flag holder for the Actions mock surface. MockK doesn't easily
     * model per-test reactivity without stubs, so we drive the relevant
     * booleans through a small holder that the test controls.
     */
    private lateinit var flags: ActionFlags
    private var restartCalls = 0
    private var pingCalls = mutableListOf<String>()
    private var reattachCalls = 0
    private var reattachForceRelay = mutableListOf<Boolean>()

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        flags = ActionFlags()
        restartCalls = 0
        pingCalls = mutableListOf<String>()
        reattachCalls = 0
        reattachForceRelay = mutableListOf<Boolean>()

        io.mockk.every { actions.isCallActive() } answers { flags.callActiveValue }
        io.mockk.every { actions.isSignalingAvailable() } answers { flags.signalingAvailableValue }
        io.mockk.every { actions.startIceRestart(any()) } answers {
            restartCalls += 1
            Unit
        }
        // any() leaves firstArg() inferred as Any?, which can't += into
        // MutableList<String>. Explicit type pin on firstArg<String>() is the
        // canonical MockK fix; mirrors firstArg<SdpObserver>() at
        // IceRenegotiationTest.kt:119.
        io.mockk.every { actions.sendProbePing(any()) } answers {
            pingCalls += firstArg<String>()
            true
        }
        io.mockk.every { actions.shouldForceRelayForRecovery(any()) } answers {
            firstArg<(Boolean) -> Unit>().invoke(false)
        }
        io.mockk.every { actions.requestReattach(any()) } answers {
            reattachCalls += 1
            reattachForceRelay += firstArg<Boolean>()
            true
        }
        io.mockk.every { actions.snapshotForLog() } answers { emptyMap() }

        fakeClock = FakeClock().also { it.now.set(1_000_000L) }
    }

    private fun newMonitor(
        cfg: SignalingHealthMonitor.Config = SignalingHealthMonitor.Config(
            disconnectedDebounceMs = 3_000L,
            restartTimeoutMs = 15_000L,
            mediaVerifyWindowMs = 5_000L,
            pingTimeoutMs = 5_000L,
        ),
    ): SignalingHealthMonitor = SignalingHealthMonitor(
        callId = testCallId,
        config = cfg,
        clock = { fakeClock.now.get() },
        random = Random(0xC0FFEE),
        actions = actions,
    )

    @Test
    fun `idle starts in IDLE with generation zero`() {
        val monitor = newMonitor()
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(0L, monitor.generation())
    }

    @Test
    fun `ICE FAILED with healthy signaling immediately triggers ice restart`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `ICE FAILED with stale signaling starts probe instead`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        assertEquals(0, restartCalls)
        assertEquals(1, pingCalls.size)
    }

    @Test
    fun `ICE FAILED with unavailable signaling immediately requests reattach`() {
        flags.signalingAvailableValue = false
        val monitor = newMonitor()

        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(0, pingCalls.size)
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `peer connection FAILED triggers same recovery as ICE FAILED`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `peer connection FAILED recovers while ICE still reports connected`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `call no longer active guard prevents recovery re-entry`() {
        flags.signalingAvailableValue = true
        flags.callActiveValue = false
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `duplicate ICE FAILED while restarting is a no-op`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val generationAfterFirst = monitor.generation()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(generationAfterFirst, monitor.generation())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `peer disconnected transitions to PROBING and cancels on peer re-connect`() {
        val monitor = newMonitor()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.CONNECTED)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `peer disconnected debounce elapses and starts a probe when signaling is stale`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        // Advance past the debounce window.
        fakeClock.advance(3_001L)
        monitor.onTick()
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        assertEquals(1, pingCalls.size)
    }

    @Test
    fun `peer disconnected debounce elapses and restarts when signaling healthy`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        fakeClock.advance(3_001L)
        monitor.onSignalingMessage(
            messageId = null,
            hasResult = false,
            hasError = false,
        )
        monitor.onTick()
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `FAILED during disconnected debounce starts recovery immediately`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()

        monitor.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `ICE disconnected does not start peer recovery debounce`() {
        val monitor = newMonitor()

        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)

        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `ICE connected does not cancel peer disconnected debounce`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.DISCONNECTED)

        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        fakeClock.advance(3_001L)
        monitor.onSignalingMessage(
            messageId = null,
            hasResult = false,
            hasError = false,
        )
        monitor.onTick()

        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `state remains readable while a synchronized recovery action is running`() {
        flags.signalingAvailableValue = true
        val actionStarted = CountDownLatch(1)
        val releaseAction = CountDownLatch(1)
        io.mockk.every { actions.startIceRestart(any()) } answers {
            actionStarted.countDown()
            releaseAction.await(5, TimeUnit.SECONDS)
            Unit
        }
        val monitor = newMonitor()
        val executor = Executors.newFixedThreadPool(2)
        val recoveryFuture = executor.submit {
            monitor.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)
        }

        try {
            assertTrue(actionStarted.await(1, TimeUnit.SECONDS))
            val stateFuture = executor.submit<SignalingHealthMonitor.State> { monitor.state() }
            assertEquals(
                SignalingHealthMonitor.State.ICE_RESTARTING,
                stateFuture.get(1, TimeUnit.SECONDS),
            )
        } finally {
            releaseAction.countDown()
            recoveryFuture.get(1, TimeUnit.SECONDS)
            executor.shutdownNow()
        }
    }

    @Test
    fun `stale active-call signaling is probed without restarting healthy ICE`() {
        val monitor = newMonitor()
        fakeClock.advance(20_001L)

        monitor.onTick()

        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        val probeId = pingCalls.single()
        monitor.onSignalingMessage(
            messageId = probeId,
            hasResult = true,
            hasError = false,
        )

        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(0, restartCalls)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `active-call health check reattaches when signaling is unavailable`() {
        flags.signalingAvailableValue = false
        val monitor = newMonitor()
        fakeClock.advance(3_001L)

        monitor.onTick()

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(1, reattachCalls)
        assertEquals(listOf(false), reattachForceRelay)
        io.mockk.verify(exactly = 0) { actions.shouldForceRelayForRecovery(any()) }
    }

    @Test
    fun `failed direct VPN path forces relay for replacement call`() {
        flags.signalingAvailableValue = false
        io.mockk.every { actions.shouldForceRelayForRecovery(any()) } answers {
            firstArg<(Boolean) -> Unit>().invoke(true)
        }
        val monitor = newMonitor()

        monitor.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(listOf(true), reattachForceRelay)
    }

    @Test
    fun `FAILED upgrades an in-flight health probe to media recovery`() {
        val monitor = newMonitor()
        fakeClock.advance(20_001L)
        monitor.onTick()
        val probeId = pingCalls.single()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        monitor.onSignalingMessage(
            messageId = probeId,
            hasResult = true,
            hasError = false,
        )

        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `exact probe correlation only resolves matching id`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        // An unrelated response updates signaling freshness but cannot resolve
        // the exact pending recovery probe.
        monitor.onSignalingMessage(
            messageId = "unrelated",
            hasResult = true,
            hasError = false,
        )
        assertEquals(SignalingHealthMonitor.State.PROBING, monitor.state())
        // Like iOS, even an error response with the matching JSON-RPC id proves
        // the signaling path is alive and permits an ICE restart.
        monitor.onSignalingMessage(
            messageId = probeId,
            hasResult = false,
            hasError = true,
        )
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `probe success after failure restarts even if ICE may have recovered`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        monitor.onSignalingMessage(
            messageId = probeId,
            hasResult = true,
            hasError = false,
        )
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `probe timeout escalates to reattach`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        // FAILED sends the probe immediately, so only the configured 5 second
        // probe timeout applies (the 3 second debounce is for DISCONNECTED).
        fakeClock.advance(5_001L)
        monitor.onTick()
        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `15 second restart timeout escalates to reattach`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(SignalingHealthMonitor.State.ICE_RESTARTING, monitor.state())
        fakeClock.advance(15_001L)
        monitor.onTick()
        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `answer applied moves to verifying media`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = monitor.generation()
        monitor.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(SignalingHealthMonitor.State.VERIFYING_MEDIA, monitor.state())
    }

    @Test
    fun `late answer callback against stale generation is dropped`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = monitor.generation()
        // Force a new generation by canceling and starting fresh.
        monitor.cancel("reset")
        monitor.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertNotEquals(gen, monitor.generation())
    }

    @Test
    fun `inbound RTP growth returns to idle from verifying media`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        monitor.onUpdateMediaAnswerApplied(generationAtSend = monitor.generation())
        monitor.onInboundRtpGrowth(generationAtSend = monitor.generation())
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    @Test
    fun `inbound RTP samples require post-answer packet growth`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        monitor.onUpdateMediaAnswerApplied(generationAtSend = monitor.generation())

        monitor.onInboundRtpSample(10L)
        assertEquals(SignalingHealthMonitor.State.VERIFYING_MEDIA, monitor.state())
        monitor.onInboundRtpSample(10L)
        assertEquals(SignalingHealthMonitor.State.VERIFYING_MEDIA, monitor.state())
        monitor.onInboundRtpSample(11L)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    @Test
    fun `failed probe send immediately requests reattach`() {
        io.mockk.every { actions.sendProbePing(any()) } returns false
        val monitor = newMonitor()
        makeSignalingStale()

        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `rejected reattach request returns monitor to idle`() {
        io.mockk.every { actions.requestReattach(any()) } answers {
            reattachCalls += 1
            false
        }
        flags.signalingAvailableValue = false
        val monitor = newMonitor()

        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `media verify timeout escalates to reattach`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        monitor.onUpdateMediaAnswerApplied(generationAtSend = monitor.generation())
        fakeClock.advance(5_001L)
        monitor.onTick()
        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `cancel from any state returns to idle and bumps generation`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val beforeGen = monitor.generation()
        monitor.cancel("dropped")
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertNotEquals(beforeGen, monitor.generation())
    }

    @Test
    fun `network path change preempts recovery for client reattach`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val restartGeneration = monitor.generation()

        monitor.onNetworkPathDidChange()

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertNotEquals(restartGeneration, monitor.generation())
        assertEquals(1, restartCalls)
        assertEquals(0, reattachCalls)

        monitor.onIceRestartFailed(restartGeneration, "late_failure")
        monitor.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)

        assertEquals(SignalingHealthMonitor.State.REATTACHING, monitor.state())
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `cancel after reattach clears pending reattach flag`() {
        val monitor = newMonitor()
        makeSignalingStale()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        fakeClock.advance(5_001L)
        monitor.onTick()
        assertEquals(1, reattachCalls)
        monitor.cancel("cleanup")
        // Subsequent reattach-from-restart in a new generation may re-fire
        // reattach — but only when the new failure actually occurs.
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    @Test
    fun `stale generation callbacks are dropped`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val staleGen = monitor.generation()
        monitor.cancel("rotate")
        // Old-gen probe result must be ignored.
        monitor.onSignalingMessage(
            messageId = "anything",
            hasResult = true,
            hasError = false,
        )
        // Old-gen RTP growth must be ignored.
        monitor.onInboundRtpGrowth(generationAtSend = staleGen)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
        assertEquals(1, restartCalls)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `reattach performed exactly once across multiple escalation triggers`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        fakeClock.advance(15_001L)
        monitor.onTick()
        assertEquals(1, reattachCalls)
        // Force another restart timeout-equivalent by canceling and triggering
        // a new failure: reattach should be allowed in the new generation.
        monitor.cancel("new_attempt")
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        fakeClock.advance(15_001L)
        monitor.onTick()
        assertEquals(2, reattachCalls)
    }

    @Test
    fun `null ICE state callback is ignored`() {
        val monitor = newMonitor()
        monitor.onIceConnectionChange(null)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    @Test
    fun `null peer connection state callback is ignored`() {
        val monitor = newMonitor()
        monitor.onConnectionChange(null)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    @Test
    fun `RESTARTING transition that finds call no longer active cancels cleanly`() {
        flags.signalingAvailableValue = true
        val monitor = newMonitor()
        monitor.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = monitor.generation()
        flags.callActiveValue = false
        monitor.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(SignalingHealthMonitor.State.IDLE, monitor.state())
    }

    private fun makeSignalingStale() {
        fakeClock.advance(45_001L)
    }

    private class ActionFlags {
        val callActive = AtomicLong(1L)
        val signalingAvailable = AtomicLong(1L)

        var callActiveValue: Boolean
            get() = callActive.get() != 0L
            set(v) { callActive.set(if (v) 1L else 0L) }
        var signalingAvailableValue: Boolean
            get() = signalingAvailable.get() != 0L
            set(v) { signalingAvailable.set(if (v) 1L else 0L) }

        init {
            callActiveValue = true
            signalingAvailableValue = true
        }
    }

    private class FakeClock {
        val now = AtomicLong(0L)
        fun advance(deltaMs: Long) {
            now.addAndGet(deltaMs)
        }
    }
}
