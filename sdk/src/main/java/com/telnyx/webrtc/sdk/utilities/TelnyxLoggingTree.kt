package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LogLevel
import timber.log.Timber

internal class TelnyxLoggingTree(logLevel: LogLevel) : Timber.Tree() {

    private val projectLogLevel = logLevel

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        when (projectLogLevel) {
            LogLevel.NONE -> {
                //NOOP
            }
            LogLevel.ERROR -> {
                if (priority == LogLevel.ERROR.priority) {
                    super.log(priority, tag, message, throwable)
                }
            }
            LogLevel.DEBUG -> {
                if (priority == LogLevel.DEBUG.priority) {
                    super.log(priority, tag, message, throwable)
                }
            }
            LogLevel.WARNING -> {
                if (priority == LogLevel.WARNING.priority) {
                    super.log(priority, tag, message, throwable)
                }
            }
            LogLevel.INFO -> {
                if (priority == LogLevel.INFO.priority) {
                    super.log(priority, tag, message, throwable)
                }
            }
            LogLevel.VERTO -> {
                if (tag == LogLevel.VERTO.name) {
                    super.log(priority, tag, message, throwable)
                }
            }
            LogLevel.ALL -> {
                super.log(priority, tag, message, throwable)
            }
        }
    }
}

