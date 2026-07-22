package com.telnyx.webrtc.sdk.peer

import android.content.Context
import com.telnyx.webrtc.sdk.Config
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the ICE server configuration produced by [Peer].
 *
 * Covers the TURNS 443 fallback added for restrictive firewall environments.
 * The fifth ICE server must always be a TURNS entry on port 443, regardless
 * of whether the caller uses the default production, default development, or
 * a custom TURN URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class IceServersTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    @RelaxedMockK
    private lateinit var mockTelnyxClient: TelnyxClient

    private val testCallId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, relaxUnitFun = true)
    }

    private fun newPeer(
        turn: String = Config.DEFAULT_TURN,
        stun: String = Config.DEFAULT_STUN
    ): Peer {
        return Peer(
            context = mockContext,
            client = mockTelnyxClient,
            providedTurn = turn,
            providedStun = stun,
            callId = testCallId
        )
    }

    @Test
    fun `default production config exposes 5 ICE servers with the documented first four`() {
        val peer = newPeer()
        val servers = peer.iceServer

        assertEquals(5, servers.size, "Expected 5 ICE servers (STUN x2, TURN UDP, TURN TCP, TURNS 443)")

        // The existing four servers remain in their original order ahead of the
        // new TURNS 443 fallback.
        assertEquals(listOf(Config.DEFAULT_STUN), servers[0].urls)
        assertEquals(listOf(Config.GOOGLE_STUN), servers[1].urls)
        assertEquals(listOf(Config.DEFAULT_TURN_UDP), servers[2].urls)
        assertEquals(listOf(Config.DEFAULT_TURN), servers[3].urls)
    }

    @Test
    fun `default production config appends production TURNS 443 as the last server`() {
        val peer = newPeer()
        val last = peer.iceServer.last()

        assertEquals(listOf(Config.DEFAULT_TURNS_443), last.urls)
        // TURN credentials are propagated to the derived TURNS entry.
        assertEquals(Config.USERNAME, last.username)
        assertEquals(Config.PASSWORD, last.password)
    }

    @Test
    fun `default development config appends dev TURNS 443 as the last server`() {
        val peer = newPeer(turn = Config.DEV_TURN)
        val servers = peer.iceServer

        assertEquals(5, servers.size)

        // TURN entries are the dev variants
        assertEquals(listOf(Config.DEV_TURN_UDP), servers[2].urls)
        assertEquals(listOf(Config.DEV_TURN), servers[3].urls)
        // TURNS 443 entry uses the dev host
        assertEquals(listOf(Config.DEV_TURNS_443), servers[4].urls)
    }

    @Test
    fun `custom TURN URL derives a TURNS 443 fallback appended as the last server`() {
        val customTurn = "turn:turn.example.com:3478?transport=tcp"
        val peer = newPeer(turn = customTurn)
        val derivedTurns443 = peer.iceServer.last().urls.single()

        assertTrue(
            derivedTurns443.startsWith("turns:turn.example.com:443"),
            "Expected derived TURNS URL on port 443, got: $derivedTurns443"
        )
        assertTrue(
            derivedTurns443.contains("transport=tcp"),
            "Expected derived TURNS URL to retain TCP transport, got: $derivedTurns443"
        )
        // Custom TURN credentials propagate to the derived TURNS entry.
        val last = peer.iceServer.last()
        assertEquals(Config.USERNAME, last.username)
        assertEquals(Config.PASSWORD, last.password)
    }

    @Test
    fun `TURNS 443 entry is always the last server regardless of input`() {
        // Default production
        val production = newPeer(turn = Config.DEFAULT_TURN).iceServer
        assertEquals(Config.DEFAULT_TURNS_443, production.last().urls.single())

        // Default development
        val development = newPeer(turn = Config.DEV_TURN).iceServer
        assertEquals(Config.DEV_TURNS_443, development.last().urls.single())

        // Custom
        val custom = newPeer(turn = "turn:custom.example.org:3478?transport=tcp").iceServer
        val customLast = custom.last().urls.single()
        assertTrue(customLast.startsWith("turns:custom.example.org:443"), "got: $customLast")
        assertTrue(customLast.contains("transport=tcp"), "got: $customLast")
    }

    @Test
    fun `custom TURN URL without transport still derives a TURNS 443 with transport tcp`() {
        // No transport and no query string
        val peer = newPeer(turn = "turn:turn.example.com:3478")
        val derivedTurns443 = peer.iceServer.last().urls.single()

        assertTrue(derivedTurns443.startsWith("turns:turn.example.com:443"), "got: $derivedTurns443")
        assertTrue(derivedTurns443.contains("transport=tcp"), "got: $derivedTurns443")
    }
}
