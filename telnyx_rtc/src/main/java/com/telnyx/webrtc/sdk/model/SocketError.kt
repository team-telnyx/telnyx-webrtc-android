/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Enum class to detail the error responses that the socket connection can receive
 * with the given [errorCode]
 *
 * @param errorCode is the Telnyx error code representation of the method, eg. Token_Error -> -32000
 *
 * @property TOKEN_ERROR there was an issue with a token - either invalid or expired
 * @property CREDENTIAL_ERROR there was an issue with the credentials used - likely invalid.
 * @property CODEC_ERROR there was an issue with the SDP handshake, likely due to codec issues.
 */
enum class SocketError(var errorCode: Int) {
    TOKEN_ERROR(-32000),
    CREDENTIAL_ERROR(-32001),
    CODEC_ERROR(-32002)
}
