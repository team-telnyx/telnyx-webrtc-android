/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import org.webrtc.IceCandidate
import java.util.*

/**
 * TxSocket interface containing the methods that the socket connection will fire
 */
interface TxSocketListener {
    /**
     * Fires when a successful login occurs on the TxSocket
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onLoginSuccessful(jsonObject: JsonObject)

    /**
     * Fires when a socket connection is established
     * @see [TxSocket]
     */
    fun onConnectionEstablished()

    /**
     * Fires when an error has occurred with the TxSocket
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onErrorReceived(jsonObject: JsonObject)

    /**
     * Fires when the TxSocket has received an indication the a call has ended or been rejected
     * @param callId, UUID of the call that has ended or been rejected
     * @see [TxSocket]
     */
    fun onByeReceived(callId: UUID)

    /**
     * Fires when a user has provided an answer to a call attempt
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onAnswerReceived(jsonObject: JsonObject)

    /**
     * Fires when an answer has been provided with additional media
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onMediaReceived(jsonObject: JsonObject)

    /**
     * Fires when the TxSocket has received an invitation to communicate
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onOfferReceived(jsonObject: JsonObject)

    /**
     * Fires once we receive a ringing socket response, containing Telnyx information
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onRingingReceived(jsonObject: JsonObject)

    /**
     * Fires when a usable IceCandidate has been received
     * @param iceCandidate, the [IceCandidate] that was received
     * @see [IceCandidate]
     */
    fun onIceCandidateReceived(iceCandidate: IceCandidate)
}