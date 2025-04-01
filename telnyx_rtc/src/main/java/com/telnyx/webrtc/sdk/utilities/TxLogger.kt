/*
 * Copyright © 2024 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import android.util.Log
import com.telnyx.webrtc.sdk.model.LogLevel

/**
 * Interface defining the contract for custom logging in the Telnyx SDK.
 * Implement this interface to create a custom logger that can receive and handle logs from the SDK.
 */
interface TxLogger {
    /**
     * Called when a log message needs to be processed.
     * 
     * @param level The severity level of the log message
     * @param tag Optional tag to categorize the log message
     * @param message The actual log message
     * @param throwable Optional throwable associated with the log message
     */
    fun log(level: LogLevel, tag: String?, message: String, throwable: Throwable? = null)
}

/**
 * Default implementation of TxLogger that uses Android Log for logging.
 * This is used when no custom logger is provided.
 */
class TxDefaultLogger : TxLogger {
    override fun log(level: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        val logTag = tag ?: "TelnyxLogging"
        val logMessage = "Default Logger $logTag: $message"

        when (level) {
            LogLevel.ERROR -> Log.e(logTag, logMessage, throwable)
            LogLevel.WARNING -> Log.w(logTag, logMessage, throwable)
            LogLevel.DEBUG -> Log.d(logTag, logMessage, throwable)
            LogLevel.INFO -> Log.i(logTag, logMessage, throwable)
            LogLevel.VERTO -> Log.d(logTag, logMessage, throwable)
            LogLevel.ALL -> Log.d(logTag, logMessage, throwable)
            LogLevel.NONE -> { /* No logging */ }
        }
    }
}
