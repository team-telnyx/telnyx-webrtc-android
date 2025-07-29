/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.telnyx.webrtc.sdk.model.SocketStatus

data class SocketResponse<out T>(
    var status: SocketStatus,
    val data: T?,
    val errorMessage: String?,
    val errorCode: Int? = null
) {
    companion object {
        fun <T> established(): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.ESTABLISHED,
                null,
                null,
                null
            )
        }

        fun <T> initialised(): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.ESTABLISHED,
                null,
                null,
                null
            )
        }

        fun <T> messageReceived(data: T): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.MESSAGERECEIVED,
                data,
                null,
                null
            )
        }

        fun <T> error(msg: String, errorCode: Int? = null): SocketResponse<T> {
            return SocketResponse(SocketStatus.ERROR, null, msg, errorCode)
        }
        fun <T> disconnect(): SocketResponse<T> {
            return SocketResponse(SocketStatus.DISCONNECT, null, null, null)
        }

        fun <T> loading(): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.LOADING,
                null,
                null,
                null
            )
        }

        fun <T> aiConversation(data: T): SocketResponse<T> {
            return SocketResponse(
                SocketStatus.MESSAGERECEIVED,
                data,
                null,
                null
            )
        }
    }
}
