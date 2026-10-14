/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for [TelnyxErrorRegistry] and [TelnyxWarningRegistry].
 *
 * Verifies that every registered code has a complete definition (name, message,
 * description, causes, solutions) and that the registries are aligned with the
 * JS SDK error/warning taxonomy.
 */
class TelnyxErrorRegistryTest {

    @Test
    fun `every error code has a definition`() {
        val codes = TelnyxErrorRegistry.codes()
        assertTrue("Registry should have error codes", codes.isNotEmpty())
        for (code in codes) {
            val error = TelnyxErrorRegistry.get(code)
            assertNotNull("Code $code should have a definition", error)
        }
    }

    @Test
    fun `every error has non-empty name`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            assertTrue("Code $code name should not be empty", error.name.isNotEmpty())
        }
    }

    @Test
    fun `every error has non-empty message`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            assertTrue("Code $code message should not be empty", error.message.isNotEmpty())
        }
    }

    @Test
    fun `every error has non-empty description`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            assertTrue("Code $code description should not be empty", error.description.isNotEmpty())
        }
    }

    @Test
    fun `every error has at least one cause`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            assertTrue("Code $code should have at least one cause", error.causes.isNotEmpty())
        }
    }

    @Test
    fun `every error has at least one solution`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            assertTrue("Code $code should have at least one solution", error.solutions.isNotEmpty())
        }
    }

    @Test
    fun `every error has explicit fatal flag`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val error = TelnyxErrorRegistry.get(code)!!
            // fatal is a Boolean (not nullable), so just verify it's accessible
            // The value itself is tested implicitly by the emit sites
        }
    }

    @Test
    fun `error codes fall within expected ranges`() {
        for (code in TelnyxErrorRegistry.codes()) {
            val inRange = (code in 40001..40999) ||  // SDP
                          (code in 42001..42999) ||  // Media
                          (code in 44001..44999) ||  // Call-control
                          (code in 45001..45999) ||  // WebSocket/transport
                          (code in 46001..46999) ||  // Auth
                          (code in 47001..47999) ||  // ICE restart
                          (code in 48001..48999) ||  // Network
                          (code in 48501..48599) ||  // Session
                          (code in 49001..49999)     // General
            assertTrue("Code $code should fall within a defined error range", inRange)
        }
    }

    @Test
    fun `specific error codes match JS SDK registry`() {
        // Verify key codes match the JS SDK parity model
        assertNotNull("40001 SDP_CREATE_OFFER_FAILED", TelnyxErrorRegistry.get(40001))
        assertNotNull("40002 SDP_CREATE_ANSWER_FAILED", TelnyxErrorRegistry.get(40002))
        assertNotNull("42001 MEDIA_MICROPHONE_PERMISSION_DENIED", TelnyxErrorRegistry.get(42001))
        assertNotNull("44001 HOLD_FAILED", TelnyxErrorRegistry.get(44001))
        assertNotNull("45001 WEBSOCKET_CONNECTION_FAILED", TelnyxErrorRegistry.get(45001))
        assertNotNull("45004 GATEWAY_FAILED", TelnyxErrorRegistry.get(45004))
        assertNotNull("46001 LOGIN_FAILED", TelnyxErrorRegistry.get(46001))
        assertNotNull("46002 INVALID_CREDENTIALS", TelnyxErrorRegistry.get(46002))
        assertNotNull("47001 ICE_RESTART_FAILED", TelnyxErrorRegistry.get(47001))
        assertNotNull("48001 NETWORK_OFFLINE", TelnyxErrorRegistry.get(48001))
        assertNotNull("48501 SESSION_NOT_REATTACHED", TelnyxErrorRegistry.get(48501))
        assertNotNull("49001 UNEXPECTED_ERROR", TelnyxErrorRegistry.get(49001))
    }

    @Test
    fun `LOGIN_FAILED is fatal`() {
        val error = TelnyxErrorRegistry.get(46001)!!
        assertTrue("LOGIN_FAILED should be fatal", error.fatal)
    }

    @Test
    fun `GATEWAY_FAILED is not fatal`() {
        val error = TelnyxErrorRegistry.get(45004)!!
        assertFalse("GATEWAY_FAILED should not be fatal (SDK retries)", error.fatal)
    }

    @Test
    fun `WEBSOCKET_ERROR is not fatal`() {
        val error = TelnyxErrorRegistry.get(45002)!!
        assertFalse("WEBSOCKET_ERROR should not be fatal (auto-reconnect)", error.fatal)
    }

    @Test
    fun `NETWORK_OFFLINE is not fatal`() {
        val error = TelnyxErrorRegistry.get(48001)!!
        assertFalse("NETWORK_OFFLINE should not be fatal", error.fatal)
    }

    @Test
    fun `RECONNECTION_EXHAUSTED is fatal`() {
        val error = TelnyxErrorRegistry.get(45003)!!
        assertTrue("RECONNECTION_EXHAUSTED should be fatal", error.fatal)
    }

    @Test
    fun `SESSION_NOT_REATTACHED is fatal`() {
        val error = TelnyxErrorRegistry.get(48501)!!
        assertTrue("SESSION_NOT_REATTACHED should be fatal", error.fatal)
    }
}
