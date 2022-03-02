/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to represent the different Gateway States that are received when a login attempt is made
 * with a given [state]
 *
 * @param state is the string value representation of the state.
 */
enum class GatewayState(var state: String) {
    UNREGED("UNREGED"),
    TRYING("TRYING"),
    REGISTER("REGISTER"),
    REGED("REGED"),
    UNREGISTER("UNREGISTER"),
    FAILED("FAILED"),
    FAIL_WAIT("FAIL_WAIT"),
    EXPIRED("EXPIRED"),
    NOREG("NOREG")
}
