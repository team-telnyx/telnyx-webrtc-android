/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.TelnyxConfig
import com.telnyx.webrtc.sdk.model.LogLevel
import timber.log.Timber

/**
 * Class that provides log levels throughout the SDK. The log level is declared during login
 *
 * @see TelnyxConfig
 */
internal class TelnyxLoggingTree(
    logLevel: LogLevel,
    private val customLogger: TxLogger? = null
) : Timber.DebugTree() {

    private val projectLogLevel = logLevel
    private val defaultLogger = TxDefaultLogger()

    override fun log(priority: Int, tag: String?, message: String, throwable: Throwable?) {
        // Determine the log level based on priority or tag
        val logLevel = when {
            priority == LogLevel.ERROR.priority -> LogLevel.ERROR
            priority == LogLevel.WARNING.priority -> LogLevel.WARNING
            priority == LogLevel.DEBUG.priority -> LogLevel.DEBUG
            priority == LogLevel.INFO.priority -> LogLevel.INFO
            tag == LogLevel.VERTO.name -> LogLevel.VERTO
            else -> LogLevel.ALL
        }

        // Check if we should log based on the project log level
        val shouldLog = when (projectLogLevel) {
            LogLevel.NONE -> false
            LogLevel.ERROR -> logLevel == LogLevel.ERROR
            LogLevel.WARNING -> logLevel == LogLevel.WARNING
            LogLevel.DEBUG -> logLevel == LogLevel.DEBUG
            LogLevel.INFO -> logLevel == LogLevel.INFO
            LogLevel.VERTO -> tag == LogLevel.VERTO.name
            LogLevel.ALL -> true
        }

        if (shouldLog) {
            // If custom logger is provided, use it
            if (customLogger != null) {
                customLogger.log(logLevel, tag, message, throwable)
            } else {
                // Otherwise, use the default Timber implementation
                super.log(priority, tag, message, throwable)
            }
        }
    }
}
