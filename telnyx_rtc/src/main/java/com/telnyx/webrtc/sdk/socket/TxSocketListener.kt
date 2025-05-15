/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import com.telnyx.webrtc.lib.IceCandidate
import java.util.*

/**
 * TxSocket interface containing the methods that the socket connection will fire
 */
interface TxSocketListener {

    /**
     * Fires once the client is ready and gateway status updates can be received
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onClientReady(jsonObject: JsonObject)

    /**
     * Fires once a Gateway state has been received. These are used to find a verified registration
     * @param gatewayState, the string representation of the gateway state received from the socket connection
     * @param sessionId, the string representation of the session ID received from the socket connection
     * @see [TxSocket]
     */
    fun onGatewayStateReceived(gatewayState: String, receivedSessionId: String?)

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
     * @param jsonObject, the socket response in a jsonObject format
     * @see [TxSocket]
     */
    fun onByeReceived(jsonObject: JsonObject)

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

    /**
     * Fires when a disablePush response is recieved
     * @param jsonObject, the socket response in a jsonObject format
     * @see [IceCandidate]
     */
    fun onDisablePushReceived(jsonObject: JsonObject)

    /**
     * Fires once a connection has been reestablished during an ongoing call and a session
     * is being reattached
     * @param jsonObject, the socket response in a jsonObject format
     */
    fun onAttachReceived(jsonObject: JsonObject)

    /**
     * Fires when network has dropped during an ongoing call. Signifies that the SDK will attempt
     * to recover once network has returned
     */
    fun setCallRecovering()

    /**
     * Fires when a ping message is received from the server, requiring a pong response
     */
    fun pingPong()
    /**
     * Fires when the socket has disconnected
     * */
    fun onDisconnect()
}
