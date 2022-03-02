/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to represent the different Cause Codes that are received when an invitation is refused
 * with a given [code]
 *
 * @param code is the numerical representation of the cause, eg. 1 -> USER_BUSY
 *
 * @property USER_BUSY This cause is used to indicate that the called party is unable to accept another call because the user busy condition has been encountered.
 * @property NORMAL_CLEARING This cause indicates that the call is being cleared because one of the users involved in the call has requested that the call be cleared. Under normal situations, the source of this cause is not the network.
 * @property INVALID_GATEWAY This cause indicates that there is an issue with the gateway in use, likely due to an invalid configuration
 * @property ORIGINATOR_CANCEL This cause indicates that the user initiating the call cancelled it before it was answered
 */
enum class CauseCode(var code: Int) {
    USER_BUSY(1),
    NORMAL_CLEARING(2),
    INVALID_GATEWAY(3),
    ORIGINATOR_CANCEL(4)
}
