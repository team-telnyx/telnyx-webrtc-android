/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.send

/**
 * A data class the represents the structure of every message received via the socket connection
 *
 * @param id a string ID that identifies each message that is sent
 * @param method the Telnyx Message Method - ie. INVITE, BYE, MODIFY, etc.
 * @param params the parameters that accompany each message, these are represented in [ParamRequest] and can be Login, Call, Bye or Modify related
 *
 * @see ParamRequest
 */
class SendingMessageBody(val id: String, val method: String, val params: ParamRequest, val jsonrpc: String = "2.0")
