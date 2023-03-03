/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.telnyx.webrtc.sdk.model.SocketStatus

data class SocketResponse<out T>(val status: SocketStatus, val data: T?, val errorMessage: String?) {
    companion object {
        fun <T> established(): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.ESTABLISHED,
                null,
                null
            )
        }

        fun <T> messageReceived(data: T): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.MESSAGERECEIVED,
                data,
                null
            )
        }

        fun <T> error(msg: String): SocketResponse<T> {
            return SocketResponse(SocketStatus.ERROR, null, msg)
        }
        fun <T> disconnect(): SocketResponse<T> {
            return SocketResponse(SocketStatus.DISCONNECT, null, null)
        }

        fun <T> loading(): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.LOADING,
                null,
                null
            )
        }
    }
}
