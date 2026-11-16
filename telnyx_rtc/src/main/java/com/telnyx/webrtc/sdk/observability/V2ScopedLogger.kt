package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.TxLogger
import com.telnyx.webrtc.sdk.model.LogLevel

/** Delegate existing app logging unchanged; only this explicit owner's text reaches KMP. */
internal class V2ScopedLogger(private val ingress: () -> CallReportV2Ingress?) {
    private fun report(level: String, message: String) {
        try { ingress()?.log(level, message) } catch (_: Exception) { /* Reporting cannot break calling. */ }
    }
    fun d(tag: String? = null, message: String, throwable: Throwable? = null) {
        report("debug", message); Logger.d(tag, message, throwable)
    }
    fun d(tag: String? = null, throwable: Throwable? = null, message: () -> String) {
        if (ingress() == null) { Logger.d(tag, throwable, message); return }
        val text = message(); report("debug", text); Logger.d(tag, text, throwable)
    }
    fun i(tag: String? = null, message: String, throwable: Throwable? = null) {
        report("info", message); Logger.i(tag, message, throwable)
    }
    fun w(tag: String? = null, message: String, throwable: Throwable? = null) {
        report("warn", message); Logger.w(tag, message, throwable)
    }
    fun e(tag: String? = null, message: String, throwable: Throwable? = null) {
        report("error", message); Logger.e(tag, message, throwable)
    }
    fun v(tag: String? = null, message: String, throwable: Throwable? = null) {
        report("trace", message); Logger.v(tag, message, throwable)
    }
    fun formatMessage(format: String, vararg args: Any?) = Logger.formatMessage(format, *args)
    fun init(logLevel: LogLevel, customLogger: TxLogger? = null) = Logger.init(logLevel, customLogger)
}
