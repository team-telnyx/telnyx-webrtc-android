/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

/**
 * A data class the represents the structure of every message received via the socket connection
 *
 * @param method the Telnyx Message Method - ie. INVITE, BYE, MODIFY, etc.
 * @param result the content of the actual message in the structure provided via [ReceivedResult]
 *
 * @see ReceivedResult
 */
data class ReceivedMessageBody(val method : String, val result : ReceivedResult?)