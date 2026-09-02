/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkErrorRegistryTest {

    @Test
    fun `all 24 error codes are registered`() {
        val expectedCodes = listOf(
            TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED,
            TelnyxErrorCodes.SDP_CREATE_ANSWER_FAILED,
            TelnyxErrorCodes.SDP_SET_LOCAL_DESCRIPTION_FAILED,
            TelnyxErrorCodes.SDP_SET_REMOTE_DESCRIPTION_FAILED,
            TelnyxErrorCodes.SDP_SEND_FAILED,
            TelnyxErrorCodes.MEDIA_MICROPHONE_PERMISSION_DENIED,
            TelnyxErrorCodes.MEDIA_DEVICE_NOT_FOUND,
            TelnyxErrorCodes.MEDIA_GET_USER_MEDIA_FAILED,
            TelnyxErrorCodes.HOLD_FAILED,
            TelnyxErrorCodes.INVALID_CALL_PARAMETERS,
            TelnyxErrorCodes.BYE_SEND_FAILED,
            TelnyxErrorCodes.SUBSCRIBE_FAILED,
            TelnyxErrorCodes.PEER_CLOSED_DURING_INIT,
            TelnyxErrorCodes.WEBSOCKET_CONNECTION_FAILED,
            TelnyxErrorCodes.WEBSOCKET_ERROR,
            TelnyxErrorCodes.RECONNECTION_EXHAUSTED,
            TelnyxErrorCodes.GATEWAY_FAILED,
            TelnyxErrorCodes.LOGIN_FAILED,
            TelnyxErrorCodes.INVALID_CREDENTIALS,
            TelnyxErrorCodes.AUTHENTICATION_REQUIRED,
            TelnyxErrorCodes.ICE_RESTART_FAILED,
            TelnyxErrorCodes.NETWORK_OFFLINE,
            TelnyxErrorCodes.SESSION_NOT_REATTACHED,
            TelnyxErrorCodes.UNEXPECTED_ERROR
        )
        assertEquals(24, expectedCodes.size)
        expectedCodes.forEach { code ->
            assertNotNull("Error code $code not registered", SdkErrorRegistry.lookup(code))
        }
    }

    @Test
    fun `lookup returns null for unknown code`() {
        assertNull(SdkErrorRegistry.lookup(99999))
    }

    @Test
    fun `create returns error with correct code and name`() {
        val error = SdkErrorRegistry.create(TelnyxErrorCodes.LOGIN_FAILED)
        assertEquals(TelnyxErrorCodes.LOGIN_FAILED, error.code)
        assertEquals("LOGIN_FAILED", error.name)
        assertEquals("Login failed", error.message)
        assertFalse(error.fatal)
    }

    @Test
    fun `create returns fatal error for SDP failures`() {
        val error = SdkErrorRegistry.create(TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED)
        assertTrue(error.fatal)
    }

    @Test
    fun `create with fatalOverride overrides registry fatal flag`() {
        val error = SdkErrorRegistry.create(
            TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED,
            fatalOverride = false
        )
        assertFalse("fatalOverride should override registry fatal=true", error.fatal)
    }

    @Test
    fun `create with unknown code returns UNEXPECTED_ERROR`() {
        val error = SdkErrorRegistry.create(99999)
        assertEquals(TelnyxErrorCodes.UNEXPECTED_ERROR, error.code)
    }

    @Test
    fun `create attaches callId and sessionId`() {
        val callId = java.util.UUID.randomUUID()
        val sessionId = "test-session"
        val error = SdkErrorRegistry.create(
            TelnyxErrorCodes.NETWORK_OFFLINE,
            callId = callId,
            sessionId = sessionId
        )
        assertEquals(callId, error.callId)
        assertEquals(sessionId, error.sessionId)
    }

    @Test
    fun `all errors have non-empty causes and solutions`() {
        val allCodes = listOf(
            TelnyxErrorCodes.SDP_CREATE_OFFER_FAILED,
            TelnyxErrorCodes.SDP_CREATE_ANSWER_FAILED,
            TelnyxErrorCodes.SDP_SET_LOCAL_DESCRIPTION_FAILED,
            TelnyxErrorCodes.SDP_SET_REMOTE_DESCRIPTION_FAILED,
            TelnyxErrorCodes.SDP_SEND_FAILED,
            TelnyxErrorCodes.MEDIA_MICROPHONE_PERMISSION_DENIED,
            TelnyxErrorCodes.MEDIA_DEVICE_NOT_FOUND,
            TelnyxErrorCodes.MEDIA_GET_USER_MEDIA_FAILED,
            TelnyxErrorCodes.HOLD_FAILED,
            TelnyxErrorCodes.INVALID_CALL_PARAMETERS,
            TelnyxErrorCodes.BYE_SEND_FAILED,
            TelnyxErrorCodes.SUBSCRIBE_FAILED,
            TelnyxErrorCodes.PEER_CLOSED_DURING_INIT,
            TelnyxErrorCodes.WEBSOCKET_CONNECTION_FAILED,
            TelnyxErrorCodes.WEBSOCKET_ERROR,
            TelnyxErrorCodes.RECONNECTION_EXHAUSTED,
            TelnyxErrorCodes.GATEWAY_FAILED,
            TelnyxErrorCodes.LOGIN_FAILED,
            TelnyxErrorCodes.INVALID_CREDENTIALS,
            TelnyxErrorCodes.AUTHENTICATION_REQUIRED,
            TelnyxErrorCodes.ICE_RESTART_FAILED,
            TelnyxErrorCodes.NETWORK_OFFLINE,
            TelnyxErrorCodes.SESSION_NOT_REATTACHED,
            TelnyxErrorCodes.UNEXPECTED_ERROR
        )
        allCodes.forEach { code ->
            val error = SdkErrorRegistry.lookup(code)!!
            assertTrue("Error $code (${error.name}) has empty causes", error.causes.isNotEmpty())
            assertTrue("Error $code (${error.name}) has empty solutions", error.solutions.isNotEmpty())
            assertTrue("Error $code (${error.name}) has empty description", error.description.isNotEmpty())
        }
    }
}
