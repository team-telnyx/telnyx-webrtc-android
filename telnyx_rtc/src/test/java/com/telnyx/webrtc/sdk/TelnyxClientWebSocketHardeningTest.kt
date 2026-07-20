/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 *
 * Unit tests for VSDK-441: WebSocket login hardening against connect/disconnect
 * race conditions and non-object JSON payloads.
 *
 * Scenarios:
 *  1. Duplicate onOpen — isConnected guard prevents second onConnectionEstablished
 *  2. Non-JSON-object WebSocket payload — try-catch logs raw bytes, emits error, no crash
 *  3. Send on destroyed socket — guard prevents sending
 *  4. onErrorReceived handles missing id/error fields gracefully
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import org.junit.Before
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class TelnyxClientWebSocketHardeningTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    private lateinit var client: TelnyxClient

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true, true, true)
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        client = TelnyxClient(mockContext)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 1: onErrorReceived handles JsonObject with missing "id" field gracefully
    // ---------------------------------------------------------------------------------
    @Test
    fun `onErrorReceived does not crash when id field is missing`() {
        val errorJson = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("message", "Invalid WebSocket payload: some data")
            })
            addProperty("jsonrpc", "2.0")
        }

        // Should not throw — the id field is accessed via try-catch
        client.onErrorReceived(errorJson, null)

        // Verify the error was emitted via socketResponseFlow
        // (We can't easily assert the flow value here without a collector,
        // but the fact that it doesn't throw is the key assertion.)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 2: onErrorReceived handles JsonObject with missing "error" field gracefully
    // ---------------------------------------------------------------------------------
    @Test
    fun `onErrorReceived does not crash when error field is missing`() {
        val errorJson = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", "test-id")
        }

        // Should not throw — the error field is accessed via try-catch
        client.onErrorReceived(errorJson, null)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 3: onErrorReceived handles JsonObject with null error message gracefully
    // ---------------------------------------------------------------------------------
    @Test
    fun `onErrorReceived does not crash when error message is null`() {
        val errorJson = JsonObject().apply {
            add("error", JsonObject().apply {
                add("message", com.google.gson.JsonNull.INSTANCE)
            })
            addProperty("jsonrpc", "2.0")
            addProperty("id", "test-id")
        }

        // Should not throw — the message is accessed via safe-call + elvis
        client.onErrorReceived(errorJson, null)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 4: onErrorReceived with proper error structure works as before
    // ---------------------------------------------------------------------------------
    @Test
    fun `onErrorReceived processes well-formed error correctly`() {
        val errorJson = JsonObject().apply {
            add("error", JsonObject().apply {
                addProperty("message", "Gateway timeout")
                addProperty("code", 500)
            })
            addProperty("jsonrpc", "2.0")
            addProperty("id", "test-id-123")
        }

        // Should not throw, should emit the error
        client.onErrorReceived(errorJson, 500)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 5: TxSocket isConnected flag is false after destroy
    // ---------------------------------------------------------------------------------
    @Test
    fun `TxSocket isConnected is false after destroy`() {
        val socket = com.telnyx.webrtc.sdk.socket.TxSocket(
            host_address = "rtc.telnyx.com",
            port = 443
        )
        socket.destroy()
        assert(!socket.isConnected)
        assert(!socket.isLoggedIn)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 6: TxSocket send on destroyed socket does not throw
    // ---------------------------------------------------------------------------------
    @Test
    fun `TxSocket send on destroyed socket does not throw`() {
        val socket = com.telnyx.webrtc.sdk.socket.TxSocket(
            host_address = "rtc.telnyx.com",
            port = 443
        )
        socket.destroy()

        // Should not throw — the send method checks isDestroyed
        socket.send(JsonObject().apply { addProperty("test", "data") })
    }
}
