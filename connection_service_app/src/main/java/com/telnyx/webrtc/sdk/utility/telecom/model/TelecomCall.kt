package com.telnyx.webrtc.sdk.utility.telecom.model

import android.os.ParcelUuid
import android.telecom.DisconnectCause
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallEndpointCompat
import kotlinx.coroutines.channels.Channel
import java.util.*

/**
 * Custom representation of a call state.
 */
sealed class TelecomCall {

    /**
     * There is no current or past calls in the stack
     */
    data object None : TelecomCall()

    /**
     * Represents a registered call with the telecom stack with the values provided by the
     * Telecom SDK
     */
    data class Registered(
        val id: ParcelUuid,
        val telnyxCallId: UUID?,
        val callAttributes: CallAttributesCompat,
        val isActive: Boolean,
        val isOnHold: Boolean,
        val isMuted: Boolean,
        val errorCode: Int?,
        val currentCallEndpoint: CallEndpointCompat?,
        val availableCallEndpoints: List<CallEndpointCompat>,
        internal val actionSource: Channel<TelecomCallAction>,
    ) : TelecomCall() {

        /**
         * @return true if it's an incoming registered call, false otherwise
         */
        fun isIncoming() = callAttributes.direction == CallAttributesCompat.DIRECTION_INCOMING

        /**
         * Sends an action to the call session. It will be processed if it's still registered.
         *
         * @return true if the action was sent, false otherwise
         */
        fun processAction(action: TelecomCallAction) = actionSource.trySend(action).isSuccess
    }

    /**
     * Represent a previously registered call that was disconnected
     */
    data class Unregistered(
        val id: ParcelUuid,
        val callAttributes: CallAttributesCompat,
        val disconnectCause: DisconnectCause,
    ) : TelecomCall()
}