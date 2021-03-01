package com.telnyx.webrtc.sdk.verto.receive

import androidx.lifecycle.Observer
import com.telnyx.webrtc.sdk.model.SocketStatus

abstract class SocketObserver<T> :  Observer<SocketResponse<T>> {

    abstract fun onConnectionEstablished()
    abstract fun onMessageReceived(data: T?)
    abstract fun onLoading()
    abstract fun onError(message:String?)

    override fun onChanged(t: SocketResponse<T>?) {
        if (t == null){
            onError("Socket Error")
        } else {
            when(t.status) {
                SocketStatus.ESTABLISHED -> onConnectionEstablished()
                SocketStatus.MESSAGERECEIVED -> onMessageReceived(t.data)
                SocketStatus.LOADING -> onLoading()
                SocketStatus.ERROR -> onError(t.errorMessage)
            }

        }
    }
}