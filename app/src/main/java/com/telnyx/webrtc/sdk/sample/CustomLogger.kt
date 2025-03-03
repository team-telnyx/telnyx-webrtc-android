/*
 * Copyright © 2024 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.sample

import android.util.Log
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.utilities.TxLogger

/**
 * Sample implementation of a custom logger for the Telnyx SDK.
 * This implementation logs to Android's LogCat but could be modified to send logs
 * to a custom analytics service or other logging system.
 */
class CustomLogger : TxLogger {
    
    private val TAG = "TelnyxCustomLogger"
    
    override fun log(level: LogLevel, tag: String?, message: String, throwable: Throwable?) {
        // Format the log message
        val formattedTag = tag ?: "Telnyx"
        val logMessage = "[$formattedTag] $message"
        
        // Log based on level
        when (level) {
            LogLevel.ERROR -> Log.e(TAG, logMessage, throwable)
            LogLevel.WARNING -> Log.w(TAG, logMessage, throwable)
            LogLevel.DEBUG -> Log.d(TAG, logMessage, throwable)
            LogLevel.INFO -> Log.i(TAG, logMessage, throwable)
            LogLevel.VERTO -> Log.v("$TAG-VERTO", logMessage, throwable)
            LogLevel.ALL -> Log.d(TAG, logMessage, throwable)
            LogLevel.NONE -> { /* No logging */ }
        }
        
        // Example: You could also send logs to your analytics service
        // MyAnalyticsService.log(
        //     level = level.name,
        //     tag = formattedTag,
        //     message = message,
        //     throwable = throwable
        // )
    }
}