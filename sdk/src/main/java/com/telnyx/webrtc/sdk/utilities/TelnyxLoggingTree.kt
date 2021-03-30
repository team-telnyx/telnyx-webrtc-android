package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LogLevel
import timber.log.Timber

internal class TelnyxLoggingTree(logLevel: LogLevel) : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        //ToDo based on provided log level...
    }
}