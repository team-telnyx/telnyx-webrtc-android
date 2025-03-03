/*
 * Copyright © 2024 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.utilities

import com.telnyx.webrtc.sdk.model.LogLevel
import timber.log.Timber

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
 * Default implementation of TxLogger that uses Timber for logging.
 * This is used when no custom logger is provided.
 */
class TxDefaultLogger : TxLogger {
    override fun log(level: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        when (level) {
            LogLevel.ERROR -> Timber.e(throwable, "Default Logger %s: %s", tag, message)
            LogLevel.WARNING -> Timber.w(throwable, "Default Logger %s: %s", tag, message)
            LogLevel.DEBUG -> Timber.d(throwable, "Default Logger %s: %s", tag, message)
            LogLevel.INFO -> Timber.i(throwable, "Default Logger %s: %s", tag, message)
            LogLevel.VERTO -> Timber.tag(LogLevel.VERTO.name).d("Default Logger %s: %s", tag, message)
            LogLevel.ALL -> Timber.d(throwable, "Default Logger %s: %s", tag, message)
            LogLevel.NONE -> { /* No logging */ }
        }
    }
}