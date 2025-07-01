/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import com.telnyx.webrtc.sdk.model.SocketStatus

/**
 * Abstract class for observing socket responses using Kotlin Flows.
 * This is the recommended approach for handling socket responses.
 * 
 * @param T The type of data contained in the socket response
 */
abstract class SocketFlowObserver<T> {

    abstract fun onConnectionEstablished()
    abstract fun onMessageReceived(data: T?)
    abstract fun onLoading()
    abstract fun onError(errorCode: Int?, message: String?)
    abstract fun onSocketDisconnect()

    /**
     * Handles the socket response and delegates to the appropriate method
     * based on the socket status.
     */
    fun handleResponse(value: SocketResponse<T>) {
        when (value.status) {
            SocketStatus.ESTABLISHED -> onConnectionEstablished()
            SocketStatus.MESSAGERECEIVED -> onMessageReceived(value.data)
            SocketStatus.LOADING -> onLoading()
            SocketStatus.ERROR -> onError(value.errorCode, value.errorMessage)
            SocketStatus.DISCONNECT -> onSocketDisconnect()
        }
    }
}