/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.verto.receive

import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.model.SocketStatus

abstract class SocketObserver<T> : Observer<SocketResponse<T>> {

    abstract fun onConnectionEstablished()
    abstract fun onMessageReceived(data: T?)
    abstract fun onLoading()
    abstract fun onError(message: String?)
    abstract fun onSocketDisconnect()

    override fun onChanged(value: SocketResponse<T>) {
        when (value.status) {
            SocketStatus.ESTABLISHED -> onConnectionEstablished()
            SocketStatus.MESSAGERECEIVED -> onMessageReceived(value.data)
            SocketStatus.LOADING -> onLoading()
            SocketStatus.ERROR -> onError(value.errorMessage)
            SocketStatus.DISCONNECT -> onSocketDisconnect()
        }
    }
}
