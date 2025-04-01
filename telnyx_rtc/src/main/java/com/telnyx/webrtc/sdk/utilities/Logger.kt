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

object Logger {
    private var logLevel: LogLevel = LogLevel.NONE
    private var customLogger: TxLogger? = null

    fun init(logLevel: LogLevel, customLogger: TxLogger? = null) {
        this.logLevel = logLevel
        this.customLogger = customLogger
    }

    fun v(tag: String? = null, message: String, throwable: Throwable? = null) {
        log(LogLevel.VERTO, tag, message, throwable)
    }

    fun d(tag: String? = null, message: String, throwable: Throwable? = null) {
        log(LogLevel.DEBUG, tag, message, throwable)
    }

    fun i(tag: String? = null, message: String, throwable: Throwable? = null) {
        log(LogLevel.INFO, tag, message, throwable)
    }

    fun w(tag: String? = null, message: String, throwable: Throwable? = null) {
        log(LogLevel.WARNING, tag, message, throwable)
    }

    fun e(tag: String? = null, message: String, throwable: Throwable? = null) {
        log(LogLevel.ERROR, tag, message, throwable)
    }

    fun log(logLevel: LogLevel, tag: String?, message: String, throwable: Throwable? = null) {
        if (!shouldLog(logLevel)) return

        if (customLogger != null) {
            customLogger?.log(logLevel, tag, message, throwable)
        } else {
            logWithAndroidLog(logLevel, tag, message, throwable)
        }
    }

    fun formatMessage(format: String, vararg args: Any?): String {
        return String.format(format, *args)
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
        val logMessage = message

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
