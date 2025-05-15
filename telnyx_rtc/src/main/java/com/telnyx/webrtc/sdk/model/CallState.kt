/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 * Enum to represent reasons for reconnection or call drop.
 */
enum class Reason(val description: String) {
    NETWORK_SWITCH("Network switched"),
    NETWORK_LOST("Network lost"),
    SERVER_ERROR("Server error")
}

/**
 *
 * Sealed class to represent the different Call States that a call can be in.
 *
 */
sealed class CallState {
    /** The call has been created. */
    object NEW : CallState()
    /** The call is being connected to the remote client. */
    object CONNECTING : CallState()
    /** The call invitation has been extended, we are waiting for an answer. */
    object RINGING : CallState()
    /** The call is active and the two clients are fully connected. */
    object ACTIVE : CallState()
    /** The user has put the call on hold. */
    object HELD : CallState()
    /** The call is finished - either party has ended the call. */
    object DONE : CallState()
    /** There was an issue creating the call. */
    object ERROR : CallState()
    /** The call was dropped as a result of network issues. */
    data class DROPPED(val reason: Reason) : CallState()
    /** The call is being reconnected after a network issue. */
    data class RECONNECTING(val reason: Reason) : CallState()

    /**
     * Helper function to get the reason for the state (if applicable).
     * @return The reason description string or null if not applicable.
     */
    fun getReason(): String? {
        return when (this) {
            is RECONNECTING -> this.reason.description
            is DROPPED -> this.reason.description
            else -> null
        }
    }

    /**
     * Returns the string representation of the class name.
     * @return The simple name of the class.
     */
    val value: String
        get() = this.javaClass.simpleName
}
