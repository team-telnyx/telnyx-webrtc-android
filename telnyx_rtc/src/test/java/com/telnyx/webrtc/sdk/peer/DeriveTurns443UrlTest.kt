package com.telnyx.webrtc.sdk.peer

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pure unit tests for [Peer.deriveTurns443Url].
 *
 * These tests call the companion-object function directly without instantiating
 * a [Peer] object, avoiding WebRTC/OpenGL initialization that would otherwise
 * fail in a JVM unit-test environment.
 */
class DeriveTurns443UrlTest {

    @Test
    fun `deriveTurns443Url overrides transport=udp to transport=tcp`() {
        val result = Peer.deriveTurns443Url("turn:turn.example.com:3478?transport=udp")

        assertEquals(
            "turns:turn.example.com:443?transport=tcp",
            result,
            "TURNS over TLS only supports TCP; transport=udp must be overridden"
        )
    }

    @Test
    fun `deriveTurns443Url does not double-prefix turns scheme`() {
        val result = Peer.deriveTurns443Url("turns:turn.example.com:443?transport=tcp")

        assertEquals(
            "turns:turn.example.com:443?transport=tcp",
            result,
            "Input already using turns: scheme must not get a second turns: prefix"
        )
    }

    @Test
    fun `deriveTurns443Url adds transport=tcp when no transport param present`() {
        val result = Peer.deriveTurns443Url("turn:turn.example.com:3478")

        assertEquals(
            "turns:turn.example.com:443?transport=tcp",
            result,
            "A TURN URL without transport param should get transport=tcp appended"
        )
    }

    @Test
    fun `deriveTurns443Url preserves extra query params and appends transport=tcp`() {
        val result = Peer.deriveTurns443Url("turn:turn.example.com:3478?foo=bar")

        assertEquals(
            "turns:turn.example.com:443?foo=bar&transport=tcp",
            result,
            "Extra query params should be preserved with transport=tcp appended"
        )
    }
}
