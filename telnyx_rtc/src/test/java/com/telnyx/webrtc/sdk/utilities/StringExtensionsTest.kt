package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class StringExtensionsTest : BaseTest() {

    @Test
    fun `encodeBase64 should not contain newlines for short strings`() {
        val shortString = "Hello World"
        val encoded = shortString.encodeBase64()

        assertFalse("Encoded string should not contain newlines", encoded.contains("\n"))
        assertFalse("Encoded string should not contain carriage returns", encoded.contains("\r"))
    }

    @Test
    fun `encodeBase64 should not contain newlines for strings longer than 57 bytes`() {
        val longString = """{"userId":"12345678-1234-1234-1234-123456789012","sessionId":"abcdefgh-abcd-abcd-abcd-abcdefghijkl","timestamp":1234567890,"metadata":{"key":"value"}}"""
        val encoded = longString.encodeBase64()

        assertFalse("Encoded string should not contain newlines", encoded.contains("\n"))
        assertFalse("Encoded string should not contain carriage returns", encoded.contains("\r"))
    }

    @Test
    fun `encodeBase64 should produce valid base64 that can be decoded`() {
        val original = "Test string for encoding"
        val encoded = original.encodeBase64()
        val decoded = encoded.decodeBase64()

        assertEquals("Decoded string should match original", original, decoded)
    }

    @Test
    fun `encodeBase64 should handle very long strings without newlines`() {
        val veryLongString = "A".repeat(500)
        val encoded = veryLongString.encodeBase64()

        assertFalse("Encoded string should not contain newlines", encoded.contains("\n"))
        assertFalse("Encoded string should not contain carriage returns", encoded.contains("\r"))

        val decoded = encoded.decodeBase64()
        assertEquals("Decoded string should match original", veryLongString, decoded)
    }

    @Test
    fun `encodeBase64 should handle JSON clientState correctly`() {
        val clientStateJson = """{"callId":"call-123","customData":"some-custom-data-that-makes-this-string-longer-than-57-bytes","timestamp":1234567890}"""
        val encoded = clientStateJson.encodeBase64()

        assertFalse("Encoded clientState should not contain newlines", encoded.contains("\n"))
        assertFalse("Encoded clientState should not contain carriage returns", encoded.contains("\r"))

        val decoded = encoded.decodeBase64()
        assertEquals("Decoded clientState should match original", clientStateJson, decoded)
    }
}
