/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to represent the different Call States that a call can be in.
 *
 * @property NEW the call has been created
 * @property CONNECTING the call is being connected to the remote client
 * @property RINGING the call invitation has been extended, we are waiting for an answer.
 * @property ACTIVE the call is active and the two clients are fully connected.
 * @property HELD the user has put the call on hold.
 * @property DONE the call is finished - either party has ended the call.
 * @property ERROR there was an issue creating the call.
 */
enum class CallState {
    NEW,
    CONNECTING,
    RECOVERING,
    RINGING,
    ACTIVE,
    HELD,
    DONE,
    ERROR
}
