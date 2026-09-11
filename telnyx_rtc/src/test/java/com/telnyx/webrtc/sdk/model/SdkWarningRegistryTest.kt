/*
 * Copyright © 2026 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SdkWarningRegistryTest {

    @Test
    fun `all 26 warning codes are registered`() {
        val expectedCodes = listOf(
            TelnyxWarningCodes.HIGH_RTT,
            TelnyxWarningCodes.HIGH_JITTER,
            TelnyxWarningCodes.HIGH_PACKET_LOSS,
            TelnyxWarningCodes.LOW_MOS,
            TelnyxWarningCodes.LOW_LOCAL_AUDIO,
            TelnyxWarningCodes.LOW_INBOUND_AUDIO,
            TelnyxWarningCodes.LOW_BYTES_RECEIVED,
            TelnyxWarningCodes.LOW_BYTES_SENT,
            TelnyxWarningCodes.RECORDING_UNAVAILABLE,
            TelnyxWarningCodes.RECORDING_BUFFER_OVERFLOW,
            TelnyxWarningCodes.ICE_CONNECTIVITY_LOST,
            TelnyxWarningCodes.ICE_GATHERING_TIMEOUT,
            TelnyxWarningCodes.ICE_GATHERING_EMPTY,
            TelnyxWarningCodes.PEER_CONNECTION_FAILED,
            TelnyxWarningCodes.ONLY_HOST_ICE_CANDIDATES,
            TelnyxWarningCodes.ANSWER_WHILE_PEER_ACTIVE,
            TelnyxWarningCodes.DUPLICATE_INBOUND_ANSWER,
            TelnyxWarningCodes.ICE_CANDIDATE_PAIR_CHANGED,
            TelnyxWarningCodes.AUDIO_INPUT_DEVICE_CHANGE_SKIPPED,
            TelnyxWarningCodes.MULTIPLE_ACTIVE_CALLS_DETECTED,
            TelnyxWarningCodes.SHARED_REMOTE_ELEMENT_OVERWRITE,
            TelnyxWarningCodes.TOKEN_EXPIRING_SOON,
            TelnyxWarningCodes.UNKNOWN_REATTACHED_SESSION,
            TelnyxWarningCodes.SIGNALING_RECOVERY_REQUIRED,
            TelnyxWarningCodes.MEDIA_RECOVERY_REQUIRED,
            TelnyxWarningCodes.RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT
        )
        assertEquals(26, expectedCodes.size)
        expectedCodes.forEach { code ->
            assertNotNull("Warning code $code not registered", SdkWarningRegistry.lookup(code))
        }
    }

    @Test
    fun `lookup returns null for unknown code`() {
        assertNull(SdkWarningRegistry.lookup(99999))
    }

    @Test
    fun `create returns warning with correct code and name`() {
        val warning = SdkWarningRegistry.create(TelnyxWarningCodes.HIGH_RTT)
        assertEquals(TelnyxWarningCodes.HIGH_RTT, warning.code)
        assertEquals("HIGH_RTT", warning.name)
        assertEquals("High network latency detected", warning.message)
    }

    @Test
    fun `create with unknown code returns UNKNOWN_WARNING`() {
        val warning = SdkWarningRegistry.create(99999)
        assertEquals(99999, warning.code)
        assertEquals("UNKNOWN_WARNING", warning.name)
    }

    @Test
    fun `create attaches callId and sessionId`() {
        val callId = java.util.UUID.randomUUID()
        val sessionId = "test-session"
        val warning = SdkWarningRegistry.create(
            TelnyxWarningCodes.ICE_CONNECTIVITY_LOST,
            callId = callId,
            sessionId = sessionId
        )
        assertEquals(callId, warning.callId)
        assertEquals(sessionId, warning.sessionId)
    }

    @Test
    fun `all warnings have non-empty causes and solutions`() {
        val allCodes = listOf(
            TelnyxWarningCodes.HIGH_RTT,
            TelnyxWarningCodes.HIGH_JITTER,
            TelnyxWarningCodes.HIGH_PACKET_LOSS,
            TelnyxWarningCodes.LOW_MOS,
            TelnyxWarningCodes.LOW_LOCAL_AUDIO,
            TelnyxWarningCodes.LOW_INBOUND_AUDIO,
            TelnyxWarningCodes.LOW_BYTES_RECEIVED,
            TelnyxWarningCodes.LOW_BYTES_SENT,
            TelnyxWarningCodes.RECORDING_UNAVAILABLE,
            TelnyxWarningCodes.RECORDING_BUFFER_OVERFLOW,
            TelnyxWarningCodes.ICE_CONNECTIVITY_LOST,
            TelnyxWarningCodes.ICE_GATHERING_TIMEOUT,
            TelnyxWarningCodes.ICE_GATHERING_EMPTY,
            TelnyxWarningCodes.PEER_CONNECTION_FAILED,
            TelnyxWarningCodes.ONLY_HOST_ICE_CANDIDATES,
            TelnyxWarningCodes.ANSWER_WHILE_PEER_ACTIVE,
            TelnyxWarningCodes.DUPLICATE_INBOUND_ANSWER,
            TelnyxWarningCodes.ICE_CANDIDATE_PAIR_CHANGED,
            TelnyxWarningCodes.AUDIO_INPUT_DEVICE_CHANGE_SKIPPED,
            TelnyxWarningCodes.MULTIPLE_ACTIVE_CALLS_DETECTED,
            TelnyxWarningCodes.SHARED_REMOTE_ELEMENT_OVERWRITE,
            TelnyxWarningCodes.TOKEN_EXPIRING_SOON,
            TelnyxWarningCodes.UNKNOWN_REATTACHED_SESSION,
            TelnyxWarningCodes.SIGNALING_RECOVERY_REQUIRED,
            TelnyxWarningCodes.MEDIA_RECOVERY_REQUIRED,
            TelnyxWarningCodes.RECONNECTION_FAILED_WITH_NO_AUTO_RECONNECT
        )
        allCodes.forEach { code ->
            val warning = SdkWarningRegistry.lookup(code)!!
            assertTrue("Warning $code (${warning.name}) has empty causes", warning.causes.isNotEmpty())
            assertTrue("Warning $code (${warning.name}) has empty solutions", warning.solutions.isNotEmpty())
            assertTrue("Warning $code (${warning.name}) has empty description", warning.description.isNotEmpty())
        }
    }
}
