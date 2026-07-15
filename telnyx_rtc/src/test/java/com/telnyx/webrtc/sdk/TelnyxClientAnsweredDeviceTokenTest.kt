/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 *
 * Unit tests for VSDK-431: `TelnyxClient.resolveAnsweredDeviceToken` resolution rules.
 *
 * The helper under test backs `TelnyxClient.acceptCall(...)` and decides what value
 * (if any) to place in the `answered_device_token` field of the `telnyx_rtc.answer`
 * verto payload. The four scenarios we must cover are spelled out in VSDK-431:
 *
 *  1. push-when-active disabled (no FCM token configured) → null/blank token omitted
 *  2. push-when-active enabled + valid token configured     → use configured FCM token
 *  3. push-when-active enabled + missing/blank token        → null/blank token omitted
 *  4. explicit token passed by app                          → explicit value wins
 *
 * The helper accesses two private fields (`credentialSessionConfig`,
 * `tokenSessionConfig`) on `TelnyxClient`, so the test injects them via reflection.
 * The fields are stored exactly the same way `credentialLogin` / `tokenLogin` /
 * `connect(credentialConfig, …)` / `connect(tokenConfig, …)` store them in
 * production — we mirror the assignment shape, not the side effects.
 */

package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
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
import kotlin.test.assertNull

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class TelnyxClientAnsweredDeviceTokenTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    private lateinit var client: TelnyxClient

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true, true, true)
        every { mockContext.getSystemService(Context.AUDIO_SERVICE) } returns mockk<AudioManager>(relaxed = true)
        // The helper only reads the credential/token session configs; it does not touch
        // the socket or the peer-connection machinery. Construct a bare client and
        // skip the connectivity callback setup that other TelnyxClient tests need.
        client = TelnyxClient(mockContext)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 1: push-when-active disabled — no session config at all.
    //             Expect: null (omitted from the verto payload).
    // ---------------------------------------------------------------------------------
    @Test
    fun `resolveAnsweredDeviceToken returns null when no session config and explicit is null`() {
        clearSessionConfigs()

        val resolved = client.resolveAnsweredDeviceToken(explicit = null)

        assertNull(resolved, "Empty session + null explicit should resolve to null so Gson omits the field.")
    }

    @Test
    fun `resolveAnsweredDeviceToken returns null when no session config and explicit is blank`() {
        clearSessionConfigs()

        assertNull(
            client.resolveAnsweredDeviceToken(explicit = ""),
            "Blank explicit should be ignored when no session config exists."
        )
        assertNull(
            client.resolveAnsweredDeviceToken(explicit = "   "),
            "Whitespace-only explicit should be ignored when no session config exists."
        )
    }

    // ---------------------------------------------------------------------------------
    // Scenario 2: push-when-active enabled with a valid configured FCM token.
    //             Expect: the configured FCM token (trimmed).
    // ---------------------------------------------------------------------------------
    @Test
    fun `resolveAnsweredDeviceToken returns credential session fcmToken when explicit is null`() {
        injectCredentialConfig(fcmToken = "fcm-token-credential")

        val resolved = client.resolveAnsweredDeviceToken(explicit = null)

        assertEquals("fcm-token-credential", resolved)
    }

    @Test
    fun `resolveAnsweredDeviceToken returns token session fcmToken when explicit is null`() {
        injectTokenConfig(fcmToken = "fcm-token-tokenconfig")

        val resolved = client.resolveAnsweredDeviceToken(explicit = null)

        assertEquals("fcm-token-tokenconfig", resolved)
    }

    @Test
    fun `resolveAnsweredDeviceToken trims surrounding whitespace from configured token`() {
        injectCredentialConfig(fcmToken = "  padded-fcm-token  ")

        val resolved = client.resolveAnsweredDeviceToken(explicit = null)

        assertEquals("padded-fcm-token", resolved)
    }

    // ---------------------------------------------------------------------------------
    // Scenario 3: push-when-active enabled but the configured token is missing/blank.
    //             Expect: null (token is not sent as an empty string).
    // ---------------------------------------------------------------------------------
    @Test
    fun `resolveAnsweredDeviceToken returns null when configured fcmToken is null`() {
        injectCredentialConfig(fcmToken = null)

        assertNull(client.resolveAnsweredDeviceToken(explicit = null))
    }

    @Test
    fun `resolveAnsweredDeviceToken returns null when configured fcmToken is empty`() {
        injectCredentialConfig(fcmToken = "")

        assertNull(client.resolveAnsweredDeviceToken(explicit = null))
    }

    @Test
    fun `resolveAnsweredDeviceToken returns null when configured fcmToken is whitespace only`() {
        injectCredentialConfig(fcmToken = "   ")

        assertNull(client.resolveAnsweredDeviceToken(explicit = null))
    }

    // ---------------------------------------------------------------------------------
    // Scenario 4: explicit token passed by the app — must always win over the configured
    //             token. This preserves the existing Android behavior documented in the
    //             `acceptCall(answeredDeviceToken = …)` KDoc.
    // ---------------------------------------------------------------------------------
    @Test
    fun `resolveAnsweredDeviceToken prefers explicit non-blank value over configured token`() {
        injectCredentialConfig(fcmToken = "fcm-token-credential")

        val resolved = client.resolveAnsweredDeviceToken(explicit = "explicit-app-token")

        assertEquals("explicit-app-token", resolved)
    }

    @Test
    fun `resolveAnsweredDeviceToken treats blank explicit as absent and falls back to configured token`() {
        injectCredentialConfig(fcmToken = "fcm-token-credential")

        assertEquals(
            "fcm-token-credential",
            client.resolveAnsweredDeviceToken(explicit = ""),
            "An empty explicit should not blow away the configured FCM token."
        )
        assertEquals(
            "fcm-token-credential",
            client.resolveAnsweredDeviceToken(explicit = "   "),
            "A whitespace-only explicit should not blow away the configured FCM token."
        )
    }

    @Test
    fun `resolveAnsweredDeviceToken trims explicit value before returning`() {
        clearSessionConfigs()

        val resolved = client.resolveAnsweredDeviceToken(explicit = "  explicit-token  ")

        assertEquals("explicit-token", resolved)
    }

    // ---------------------------------------------------------------------------------
    // Backstop: when neither side has a usable token we must not emit an empty string,
    // even if the caller passes a non-blank explicit AND the session has a blank token
    // (defensive against future config typos).
    // ---------------------------------------------------------------------------------
    @Test
    fun `resolveAnsweredDeviceToken returns null when explicit is blank and configured is blank`() {
        injectCredentialConfig(fcmToken = "")

        assertNull(
            client.resolveAnsweredDeviceToken(explicit = ""),
            "Both blank must collapse to null so the verto payload omits the field."
        )
    }

    // ---------------------------------------------------------------------------------
    // Test helpers — manipulate the same private fields the production login methods
    // set, so the helper under test sees a realistic session state.
    // ---------------------------------------------------------------------------------

    private fun clearSessionConfigs() {
        setField("credentialSessionConfig", null)
        setField("tokenSessionConfig", null)
    }

    private fun injectCredentialConfig(fcmToken: String?) {
        setField(
            "credentialSessionConfig",
            CredentialConfig(
                sipUser = "<SIP_USER>",
                sipPassword = "<SIP_PASSWORD>",
                sipCallerIDName = "Tester",
                sipCallerIDNumber = "+10000000000",
                fcmToken = fcmToken,
                ringtone = null,
                ringBackTone = null,
                logLevel = LogLevel.NONE,
            )
        )
        // Ensure the token config can't shadow the credential config in this test.
        setField("tokenSessionConfig", null)
    }

    private fun injectTokenConfig(fcmToken: String?) {
        setField("credentialSessionConfig", null)
        setField(
            "tokenSessionConfig",
            TokenConfig(
                sipToken = "<SIP_TOKEN>",
                sipCallerIDName = "Tester",
                sipCallerIDNumber = "+10000000000",
                fcmToken = fcmToken,
                ringtone = null,
                ringBackTone = null,
                logLevel = LogLevel.NONE,
            )
        )
    }

    private fun setField(name: String, value: Any?) {
        val field = TelnyxClient::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(client, value)
    }
}
