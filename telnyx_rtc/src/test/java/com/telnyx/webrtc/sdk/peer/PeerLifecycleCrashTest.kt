package com.telnyx.webrtc.sdk.peer

import android.content.Context
import com.telnyx.webrtc.sdk.Call
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.stats.DebugDataCollector
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.PeerConnectionFactory
import com.telnyx.webrtc.lib.SessionDescription
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.just
import io.mockk.every
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import kotlin.concurrent.thread

/**
 * Regression tests for VSUP-324 (native crashes during peer teardown):
 *
 * 1. Double dispose — overlapping teardown callers (e.g. endCall racing
 *    onByeReceived on the socket thread) must not run close()/dispose()
 *    twice on the same PeerConnection.
 * 2. Use-after-free — a native read that is already in flight when
 *    disconnect() starts must finish before dispose() frees the native object,
 *    and reads that start during teardown must return null without blocking.
 *
 * The concurrent tests block inside mocked native calls with latches so the
 * interleaving is deterministic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeerLifecycleCrashTest : BaseTest() {

    @RelaxedMockK
    private lateinit var mockContext: Context

    @RelaxedMockK
    private lateinit var mockTelnyxClient: TelnyxClient

    @RelaxedMockK
    private lateinit var mockCall: Call

    @RelaxedMockK
    private lateinit var mockPeerConnection: PeerConnection

    @RelaxedMockK
    private lateinit var mockSocket: TxSocket

    @RelaxedMockK
    private lateinit var mockPeerConnectionFactory: PeerConnectionFactory

    @RelaxedMockK
    private lateinit var mockDebugDataCollector: DebugDataCollector

    private lateinit var peer: Peer
    private val testCallId = UUID.randomUUID()

    @Before
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
        Dispatchers.setMain(StandardTestDispatcher())

        val callsMap = mutableMapOf<UUID, Call>()
        callsMap[testCallId] = mockCall
        every { mockTelnyxClient.calls } returns callsMap
        every { mockCall.callStateFlow.value } returns CallState.ACTIVE
        every { mockCall.updateCallState(any()) } just Runs
        every { mockTelnyxClient.sendUpdateMediaMessage(any(), any(), any()) } just Runs
        every { mockTelnyxClient.isSocketConnected() } returns true
        every { mockTelnyxClient.getUseTrickleIce() } returns false
        every { mockTelnyxClient.debugDataCollector } returns mockDebugDataCollector
        every { mockTelnyxClient.sessid } returns "session-id"
        every { mockTelnyxClient.socket } returns mockSocket

        val observerSlot = io.mockk.slot<PeerConnection.Observer>()
        every {
            mockPeerConnectionFactory.createPeerConnection(
                any<PeerConnection.RTCConfiguration>(),
                capture(observerSlot),
            )
        } returns mockPeerConnection

        peer = Peer(
            context = mockContext,
            client = mockTelnyxClient,
            callId = testCallId,
            peerConnectionFactoryOverride = mockPeerConnectionFactory,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        // disconnect() is idempotent, so releasing an already-torn-down peer is a no-op.
        peer.release()
    }

    @Test
    fun `concurrent disconnect runs close and dispose exactly once`() {
        val closeEntered = CountDownLatch(1)
        val allowClose = CountDownLatch(1)
        every { mockPeerConnection.close() } answers {
            closeEntered.countDown()
            allowClose.await(TIMEOUT_S, TimeUnit.SECONDS)
            Unit
        }

        val first = thread { peer.disconnect() }
        assertTrue(closeEntered.await(TIMEOUT_S, TimeUnit.SECONDS))

        // Second teardown arrives while the first is still inside close().
        val second = thread { peer.disconnect() }
        second.join(TIMEOUT_MS)
        assertFalse("Losing teardown caller must return immediately", second.isAlive)

        allowClose.countDown()
        first.join(TIMEOUT_MS)

        verify(exactly = 1) { mockPeerConnection.close() }
        verify(exactly = 1) { mockPeerConnection.dispose() }
    }

    @Test
    fun `release after disconnect does not dispose twice`() {
        peer.disconnect()
        peer.release()

        verify(exactly = 1) { mockPeerConnection.close() }
        verify(exactly = 1) { mockPeerConnection.dispose() }
    }

    @Test
    fun `descriptions return null after disconnect without touching native object`() {
        peer.disconnect()

        assertNull(peer.getLocalDescription())
        assertNull(peer.getRemoteDescription())
        verify(exactly = 0) { mockPeerConnection.localDescription }
        verify(exactly = 0) { mockPeerConnection.remoteDescription }
    }

    @Test
    fun `disconnect clears peerConnection reference before closing`() {
        var peerConnectionRefDuringClose: Any? = null
        every { mockPeerConnection.close() } answers {
            peerConnectionRefDuringClose = peer.peerConnection
            Unit
        }

        peer.disconnect()

        assertNull("Reference must be cleared before close()/dispose() run", peerConnectionRefDuringClose)
        assertEquals("isDisposed must be set when teardown claims the peer", true, peer.isDisposed.get())
    }

    @Test
    fun `dispose waits for an in-flight native read to finish`() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        val description = SessionDescription(SessionDescription.Type.OFFER, "v=0")
        every { mockPeerConnection.localDescription } answers {
            readEntered.countDown()
            allowRead.await(TIMEOUT_S, TimeUnit.SECONDS)
            events.add("read-finished")
            description
        }
        every { mockPeerConnection.dispose() } answers {
            events.add("dispose")
            Unit
        }

        val readResult = AtomicReference<SessionDescription?>()
        val reader = thread { readResult.set(peer.getLocalDescription()) }
        assertTrue(readEntered.await(TIMEOUT_S, TimeUnit.SECONDS))

        val disposer = thread { peer.disconnect() }
        disposer.join(BLOCKED_CHECK_MS)
        assertTrue("disconnect() must wait for the in-flight reader", disposer.isAlive)
        verify(exactly = 0) { mockPeerConnection.dispose() }

        allowRead.countDown()
        reader.join(TIMEOUT_MS)
        disposer.join(TIMEOUT_MS)

        assertSame(description, readResult.get())
        assertEquals(listOf("read-finished", "dispose"), events.toList())
    }

    @Test
    fun `read during dispose returns null without blocking`() {
        val disposeEntered = CountDownLatch(1)
        val allowDispose = CountDownLatch(1)
        every { mockPeerConnection.dispose() } answers {
            disposeEntered.countDown()
            allowDispose.await(TIMEOUT_S, TimeUnit.SECONDS)
            Unit
        }

        val disposer = thread { peer.disconnect() }
        assertTrue(disposeEntered.await(TIMEOUT_S, TimeUnit.SECONDS))

        // Simulates a stats handler or signaling-thread callback racing teardown.
        val startedAt = System.nanoTime()
        assertNull(peer.getLocalDescription())
        assertNull(peer.withLiveConnection { it.iceConnectionState() })
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue("Reader must not block on teardown (took ${elapsedMs}ms)", elapsedMs < BLOCKED_CHECK_MS)

        allowDispose.countDown()
        disposer.join(TIMEOUT_MS)
        verify(exactly = 0) { mockPeerConnection.localDescription }
    }

    @Test
    fun `stuck reader makes disconnect skip dispose instead of hanging`() {
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        every { mockPeerConnection.localDescription } answers {
            readEntered.countDown()
            allowRead.await(TIMEOUT_S, TimeUnit.SECONDS)
            null
        }

        val reader = thread { peer.getLocalDescription() }
        assertTrue(readEntered.await(TIMEOUT_S, TimeUnit.SECONDS))

        // Returns after the bounded wait; leaking the closed connection beats
        // freeing it under the reader or deadlocking.
        peer.disconnect()

        verify(exactly = 1) { mockPeerConnection.close() }
        verify(exactly = 0) { mockPeerConnection.dispose() }

        allowRead.countDown()
        reader.join(TIMEOUT_MS)
    }

    private companion object {
        const val TIMEOUT_S = 5L
        const val TIMEOUT_MS = 5_000L
        const val BLOCKED_CHECK_MS = 200L
    }
}
