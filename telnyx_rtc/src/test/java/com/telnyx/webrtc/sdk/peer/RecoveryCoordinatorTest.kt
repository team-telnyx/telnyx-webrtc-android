/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.webrtc.PeerConnection
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Unit tests for [RecoveryCoordinator].
 *
 * Covers the documented state machine invariants:
 *  - duplicate ICE/peer-connection callbacks are no-ops
 *  - transient disconnected debounce (3s) cancels on re-connection
 *  - exact probe correlation (unrelated inbound frames update liveness but
 *    do not resolve the probe)
 *  - probe timeout escalates to reattach
 *  - 15s restart timeout escalates to reattach
 *  - already-connected guard prevents re-entry
 *  - RTP verification success returns the machine to IDLE
 *  - RTP verification timeout escalates to reattach
 *  - terminal-call cancellation cancels all timers/jobs
 *  - reattach is performed at most once per recovery generation
 *  - late callbacks against a stale generation are dropped
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class RecoveryCoordinatorTest : BaseTest() {

    @RelaxedMockK
    private lateinit var actions: RecoveryCoordinator.Actions

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

    @BeforeEach
    fun setup() {
        flags = ActionFlags()
        restartCalls = 0
        pingCalls = mutableListOf()
        reattachCalls = 0

        io.mockk.every { actions.isCallActive() } answers { flags.callActiveValue }
        io.mockk.every { actions.isPeerConnectedOrCompleted() } answers { flags.peerConnectedValue }
        io.mockk.every { actions.isSignalingHealthy() } answers { flags.signalingHealthyValue }
        io.mockk.every { actions.startIceRestart() } answers {
            restartCalls += 1
            Unit
        }
        io.mockk.every { actions.sendProbePing(any()) } answers {
            pingCalls += firstArg()
            Unit
        }
        io.mockk.every { actions.requestReattach() } answers {
            reattachCalls += 1
            Unit
        }
        io.mockk.every { actions.snapshotForLog() } answers { emptyMap() }

        fakeClock = FakeClock().also { it.now.set(1_000_000L) }
    }

    private fun newCoordinator(
        cfg: RecoveryCoordinator.Config = RecoveryCoordinator.Config(
            disconnectedDebounceMs = 3_000L,
            restartTimeoutMs = 15_000L,
            mediaVerifyWindowMs = 5_000L,
            pingTimeoutMs = 5_000L,
        ),
    ): RecoveryCoordinator = RecoveryCoordinator(
        callId = testCallId,
        config = cfg,
        clock = { fakeClock.now.get() },
        random = Random(0xC0FFEE),
        actions = actions,
    )

    @Test
    fun `idle starts in IDLE with generation zero`() {
        val coord = newCoordinator()
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(0L, coord.generation())
    }

    @Test
    fun `ICE FAILED with healthy signaling immediately triggers ice restart`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.ICE_RESTARTING, coord.state())
        assertEquals(1, restartCalls)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `ICE FAILED with stale signaling starts probe instead`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        assertEquals(0, restartCalls)
        assertEquals(1, pingCalls.size)
    }

    @Test
    fun `peer connection FAILED triggers same recovery as ICE FAILED`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onConnectionChange(PeerConnection.PeerConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.ICE_RESTARTING, coord.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `already-connected guard prevents recovery re-entry`() {
        flags.signalingHealthyValue = true
        flags.peerConnectedValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `call no longer active guard prevents recovery re-entry`() {
        flags.signalingHealthyValue = true
        flags.callActiveValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `duplicate ICE FAILED while restarting is a no-op`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val generationAfterFirst = coord.generation()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(generationAfterFirst, coord.generation())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `disconnected transitions to PROBING and cancels on re-connect`() {
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.CONNECTED)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `disconnected debounce elapses and starts a probe when signaling unhealthy`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        // Advance past the debounce window.
        fakeClock.advance(3_001L)
        coord.onTick()
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        assertEquals(1, pingCalls.size)
    }

    @Test
    fun `disconnected debounce elapses and restarts when signaling healthy`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.DISCONNECTED)
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        fakeClock.advance(3_001L)
        coord.onTick()
        assertEquals(RecoveryCoordinator.State.ICE_RESTARTING, coord.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `exact probe correlation only resolves matching id`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        // Unrelated inbound frame is a no-op.
        coord.onProbeResult(probeId = "unrelated", success = true)
        assertEquals(RecoveryCoordinator.State.PROBING, coord.state())
        // Matching success transitions to ICE_RESTARTING.
        coord.onProbeResult(probeId = probeId, success = true)
        assertEquals(RecoveryCoordinator.State.ICE_RESTARTING, coord.state())
        assertEquals(1, restartCalls)
    }

    @Test
    fun `probe success that finds peer already connected cancels cleanly`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        // Between probe start and result the peer re-connected.
        flags.peerConnectedValue = true
        coord.onProbeResult(probeId = probeId, success = true)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(0, restartCalls)
    }

    @Test
    fun `probe failure escalates to reattach`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        coord.onProbeResult(probeId = probeId, success = false)
        assertEquals(RecoveryCoordinator.State.REATTACHING, coord.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `probe timeout escalates to reattach`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        // debounce (3s) + ping timeout (5s) = 8s total before reattach.
        fakeClock.advance(8_001L)
        coord.onTick()
        assertEquals(RecoveryCoordinator.State.REATTACHING, coord.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `15 second restart timeout escalates to reattach`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        assertEquals(RecoveryCoordinator.State.ICE_RESTARTING, coord.state())
        fakeClock.advance(15_001L)
        coord.onTick()
        assertEquals(RecoveryCoordinator.State.REATTACHING, coord.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `answer applied moves to verifying media`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = coord.generation()
        coord.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(RecoveryCoordinator.State.VERIFYING_MEDIA, coord.state())
    }

    @Test
    fun `late answer callback against stale generation is dropped`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = coord.generation()
        // Force a new generation by canceling and starting fresh.
        coord.cancel("reset")
        coord.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertNotEquals(gen, coord.generation())
    }

    @Test
    fun `inbound RTP growth returns to idle from verifying media`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        coord.onUpdateMediaAnswerApplied(generationAtSend = coord.generation())
        coord.onInboundRtpGrowth(generationAtSend = coord.generation())
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
    }

    @Test
    fun `media verify timeout escalates to reattach`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        coord.onUpdateMediaAnswerApplied(generationAtSend = coord.generation())
        fakeClock.advance(5_001L)
        coord.onTick()
        assertEquals(RecoveryCoordinator.State.REATTACHING, coord.state())
        assertEquals(1, reattachCalls)
    }

    @Test
    fun `cancel from any state returns to idle and bumps generation`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val beforeGen = coord.generation()
        coord.cancel("dropped")
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertNotEquals(beforeGen, coord.generation())
    }

    @Test
    fun `cancel after reattach clears pending reattach flag`() {
        flags.signalingHealthyValue = false
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val probeId = pingCalls.single()
        coord.onProbeResult(probeId = probeId, success = false)
        assertEquals(1, reattachCalls)
        coord.cancel("cleanup")
        // Subsequent reattach-from-restart in a new generation may re-fire
        // reattach — but only when the new failure actually occurs.
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
    }

    @Test
    fun `stale generation callbacks are dropped`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val staleGen = coord.generation()
        coord.cancel("rotate")
        // Old-gen probe result must be ignored.
        coord.onProbeResult(probeId = "anything", success = true)
        // Old-gen RTP growth must be ignored.
        coord.onInboundRtpGrowth(generationAtSend = staleGen)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
        assertEquals(1, restartCalls)
        assertEquals(0, reattachCalls)
    }

    @Test
    fun `reattach performed exactly once across multiple escalation triggers`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        fakeClock.advance(15_001L)
        coord.onTick()
        assertEquals(1, reattachCalls)
        // Force another restart timeout-equivalent by canceling and triggering
        // a new failure: reattach should be allowed in the new generation.
        coord.cancel("new_attempt")
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        fakeClock.advance(15_001L)
        coord.onTick()
        assertEquals(2, reattachCalls)
    }

    @Test
    fun `null ICE state callback is ignored`() {
        val coord = newCoordinator()
        coord.onIceConnectionChange(null)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
    }

    @Test
    fun `null peer connection state callback is ignored`() {
        val coord = newCoordinator()
        coord.onConnectionChange(null)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
    }

    @Test
    fun `RESTARTING transition that finds call no longer active cancels cleanly`() {
        flags.signalingHealthyValue = true
        val coord = newCoordinator()
        coord.onIceConnectionChange(PeerConnection.IceConnectionState.FAILED)
        val gen = coord.generation()
        flags.callActiveValue = false
        coord.onUpdateMediaAnswerApplied(generationAtSend = gen)
        assertEquals(RecoveryCoordinator.State.IDLE, coord.state())
    }

    private class ActionFlags {
        val callActive = AtomicLong(1L)
        val peerConnected = AtomicLong(0L)
        val signalingHealthy = AtomicLong(1L)

        var callActiveValue: Boolean
            get() = callActive.get() != 0L
            set(v) { callActive.set(if (v) 1L else 0L) }
        var peerConnectedValue: Boolean
            get() = peerConnected.get() != 0L
            set(v) { peerConnected.set(if (v) 1L else 0L) }
        var signalingHealthyValue: Boolean
            get() = signalingHealthy.get() != 0L
            set(v) { signalingHealthy.set(if (v) 1L else 0L) }

        init {
            callActiveValue = true
            peerConnectedValue = false
            signalingHealthyValue = true
        }
    }

    private class FakeClock {
        val now = AtomicLong(0L)
        fun advance(deltaMs: Long) {
            now.addAndGet(deltaMs)
        }
    }
}
