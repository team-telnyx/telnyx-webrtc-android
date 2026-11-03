package com.telnyx.webrtc.sdk.utilities

import android.util.Base64
import java.util.Date
import org.json.JSONObject

/**
 * Minimal JWT payload parser used for token expiry checks.
 *
 * JS parity (VSDK-393): mirrors BaseSession._checkTokenExpiry — the SDK parses
 * the `login_token` JWT payload to schedule a TOKEN_EXPIRING_SOON warning
 * TOKEN_EXPIRY_WARNING_SECONDS before the token expires.
 *
 * Deliberately dependency-free: no signature verification, no library. Only the
 * `exp` claim is read (numeric, epoch seconds). Invalid tokens yield null so
 * callers can skip silently, matching the JS "not a decodable JWT" catch path.
 */
internal object JwtUtils {

    /** Warning lead time before expiry, in seconds (mirrors JS BaseSession constant). */
    const val TOKEN_EXPIRY_WARNING_SECONDS: Long = 120L

    /**
     * Extracts the `exp` claim from a JWT, if the token is well-formed.
     *
     * @param token the raw JWT string, or null.
     * @return the `exp` claim as epoch seconds, or null when the token is not a
     * decodable 3-part JWT with a numeric `exp`.
     */
    fun parseTokenExpiry(token: String?): Long? {
        if (token.isNullOrBlank()) return null
        val parts = token.split('.')
        if (parts.size != 3) return null
        return try {
            val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            val exp = JSONObject(payload).optLong("exp", -1L)
            if (exp > 0) exp else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Seconds from now until the token expires, or null when unknown.
     * Negative values mean the token is already expired.
     */
    fun secondsUntilExpiry(token: String?): Long? {
        val exp = parseTokenExpiry(token) ?: return null
        return exp - Date().time / 1000L
    }
}
