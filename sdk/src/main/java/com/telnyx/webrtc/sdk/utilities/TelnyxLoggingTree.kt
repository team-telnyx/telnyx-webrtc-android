package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LogLevel
import timber.log.Timber

internal class TelnyxLoggingTree(logLevel: LogLevel) : Timber.Tree() {

    private val projectLogLevel = logLevel

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        when (projectLogLevel) {
            LogLevel.NONE -> {

            }
            LogLevel.ERROR -> {

            }
            LogLevel.WARNING -> {

            }
            LogLevel.SUCCESS -> {

            }
            LogLevel.INFO -> {

            }
            LogLevel.VERTO -> {

            }
        }
    }
}