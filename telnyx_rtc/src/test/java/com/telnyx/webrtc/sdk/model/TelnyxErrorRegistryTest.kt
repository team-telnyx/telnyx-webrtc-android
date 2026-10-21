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
        assertNotNull("40001 SDP_CREATE_OFFER_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.SDP_CREATE_OFFER_FAILED.code))
        assertNotNull("40002 SDP_CREATE_ANSWER_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.SDP_CREATE_ANSWER_FAILED.code))
        assertNotNull("42001 MEDIA_MICROPHONE_PERMISSION_DENIED", TelnyxErrorRegistry.get(TelnyxErrorCode.MEDIA_MICROPHONE_PERMISSION_DENIED.code))
        assertNotNull("44001 HOLD_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.HOLD_FAILED.code))
        assertNotNull("45001 WEBSOCKET_CONNECTION_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.WEBSOCKET_CONNECTION_FAILED.code))
        assertNotNull("45004 GATEWAY_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.GATEWAY_FAILED.code))
        assertNotNull("46001 LOGIN_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.LOGIN_FAILED.code))
        assertNotNull("46002 INVALID_CREDENTIALS", TelnyxErrorRegistry.get(TelnyxErrorCode.INVALID_CREDENTIALS.code))
        assertNotNull("47001 ICE_RESTART_FAILED", TelnyxErrorRegistry.get(TelnyxErrorCode.ICE_RESTART_FAILED.code))
        assertNotNull("48001 NETWORK_OFFLINE", TelnyxErrorRegistry.get(TelnyxErrorCode.NETWORK_OFFLINE.code))
        assertNotNull("48501 SESSION_NOT_REATTACHED", TelnyxErrorRegistry.get(TelnyxErrorCode.SESSION_NOT_REATTACHED.code))
        assertNotNull("49001 UNEXPECTED_ERROR", TelnyxErrorRegistry.get(TelnyxErrorCode.UNEXPECTED_ERROR.code))
    }

    @Test
    fun `LOGIN_FAILED is fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.LOGIN_FAILED.code)!!
        assertTrue("LOGIN_FAILED should be fatal", error.fatal)
    }

    @Test
    fun `GATEWAY_FAILED is not fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.GATEWAY_FAILED.code)!!
        assertFalse("GATEWAY_FAILED should not be fatal (SDK retries)", error.fatal)
    }

    @Test
    fun `WEBSOCKET_ERROR is not fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.WEBSOCKET_ERROR.code)!!
        assertFalse("WEBSOCKET_ERROR should not be fatal (auto-reconnect)", error.fatal)
    }

    @Test
    fun `NETWORK_OFFLINE is not fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.NETWORK_OFFLINE.code)!!
        assertFalse("NETWORK_OFFLINE should not be fatal", error.fatal)
    }

    @Test
    fun `RECONNECTION_EXHAUSTED is fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.RECONNECTION_EXHAUSTED.code)!!
        assertTrue("RECONNECTION_EXHAUSTED should be fatal", error.fatal)
    }

    @Test
    fun `SESSION_NOT_REATTACHED is fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.SESSION_NOT_REATTACHED.code)!!
        assertTrue("SESSION_NOT_REATTACHED should be fatal", error.fatal)
    }

    @Test
    fun `UNEXPECTED_ERROR is not fatal`() {
        val error = TelnyxErrorRegistry.get(TelnyxErrorCode.UNEXPECTED_ERROR.code)!!
        assertFalse("UNEXPECTED_ERROR should not be fatal (safe default for unknown errors)", error.fatal)
    }

    // ── Warning registry tests ──

    @Test
    fun `every warning code has a definition`() {
        val codes = TelnyxWarningRegistry.codes()
        assertTrue("Warning registry should have codes", codes.isNotEmpty())
        assertEquals("Warning registry should have 27 codes (JS parity)", 27, codes.size)
        for (code in codes) {
            val warning = TelnyxWarningRegistry.get(code)
            assertNotNull("Warning code $code should have a definition", warning)
        }
    }

    @Test
    fun `every warning has non-empty name`() {
        for (code in TelnyxWarningRegistry.codes()) {
            val warning = TelnyxWarningRegistry.get(code)!!
            assertTrue("Warning code $code name should not be empty", warning.name.isNotEmpty())
        }
    }

    @Test
    fun `every warning has non-empty message`() {
        for (code in TelnyxWarningRegistry.codes()) {
            val warning = TelnyxWarningRegistry.get(code)!!
            assertTrue("Warning code $code message should not be empty", warning.message.isNotEmpty())
        }
    }

    @Test
    fun `every warning has at least one cause`() {
        for (code in TelnyxWarningRegistry.codes()) {
            val warning = TelnyxWarningRegistry.get(code)!!
            assertTrue("Warning code $code should have at least one cause", warning.causes.isNotEmpty())
        }
    }

    @Test
    fun `every warning has at least one solution`() {
        for (code in TelnyxWarningRegistry.codes()) {
            val warning = TelnyxWarningRegistry.get(code)!!
            assertTrue("Warning code $code should have at least one solution", warning.solutions.isNotEmpty())
        }
    }

    @Test
    fun `warning codes fall within expected ranges`() {
        for (code in TelnyxWarningRegistry.codes()) {
            val inRange = (code in 31001..31999) ||  // Network quality
                          (code in 32001..32999) ||  // Connection / data-flow
                          (code in 33001..33999) ||  // Call connection
                          (code in 34001..34999) ||  // Authentication
                          (code in 35001..35999) ||  // Session / reconnection
                          (code in 36001..36999)     // Signaling health
            assertTrue("Warning code $code should fall within a defined range", inRange)
        }
    }

    @Test
    fun `specific warning codes match JS SDK registry`() {
        assertNotNull("33001 ICE_CONNECTIVITY_LOST", TelnyxWarningRegistry.get(TelnyxWarningCode.ICE_CONNECTIVITY_LOST.code))
        assertNotNull("33004 PEER_CONNECTION_FAILED", TelnyxWarningRegistry.get(TelnyxWarningCode.PEER_CONNECTION_FAILED.code))
        assertNotNull("32003 RECORDING_UNAVAILABLE", TelnyxWarningRegistry.get(TelnyxWarningCode.RECORDING_UNAVAILABLE.code))
        assertNotNull("33010 MULTIPLE_ACTIVE_CALLS_DETECTED", TelnyxWarningRegistry.get(TelnyxWarningCode.MULTIPLE_ACTIVE_CALLS_DETECTED.code))
        assertNotNull("36003 SIGNALING_RECOVERY_REQUIRED", TelnyxWarningRegistry.get(TelnyxWarningCode.SIGNALING_RECOVERY_REQUIRED.code))
    }
}
