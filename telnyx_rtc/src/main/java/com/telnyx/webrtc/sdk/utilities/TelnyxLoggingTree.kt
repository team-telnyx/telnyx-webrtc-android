/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

/**
 * Class that provides log levels throughout the SDK. The log level is declared during login
 *
 * @see TelnyxConfig
 */
import android.util.Log
import com.telnyx.webrtc.sdk.model.LogLevel

internal class Logger(
    private val logLevel: LogLevel,
    private val customLogger: TxLogger? = null
) {
    fun log(logLevel: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        if (!shouldLog(logLevel)) return

        if (customLogger != null) {
            customLogger.log(logLevel, tag, message, throwable)
        } else {
            logWithAndroidLog(logLevel, tag, message, throwable)
        }
    }

    private fun shouldLog(logLevel: LogLevel): Boolean {
        return when (this.logLevel) {
            LogLevel.NONE -> false
            LogLevel.ERROR -> logLevel == LogLevel.ERROR
            LogLevel.WARNING -> logLevel == LogLevel.WARNING || logLevel == LogLevel.ERROR
            LogLevel.DEBUG -> logLevel == LogLevel.DEBUG || logLevel == LogLevel.WARNING || logLevel == LogLevel.ERROR
            LogLevel.INFO -> logLevel == LogLevel.INFO || logLevel == LogLevel.DEBUG || logLevel == LogLevel.WARNING || logLevel == LogLevel.ERROR
            LogLevel.VERTO -> logLevel == LogLevel.VERTO
            LogLevel.ALL -> true
        }
    }

    private fun logWithAndroidLog(logLevel: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        val logTag = tag ?: "TelnyxLogging"
        val logMessage = "Default Logger $logTag: $message"

        when (logLevel) {
            LogLevel.ERROR -> Log.e(logTag, logMessage, throwable)
            LogLevel.WARNING -> Log.w(logTag, logMessage, throwable)
            LogLevel.DEBUG -> Log.d(logTag, logMessage, throwable)
            LogLevel.INFO -> Log.i(logTag, logMessage, throwable)
            LogLevel.VERTO, LogLevel.ALL -> Log.d(logTag, logMessage, throwable) // Default to DEBUG for VERTO & ALL
            LogLevel.NONE -> { /* No logging */ }
        }
    }
}

