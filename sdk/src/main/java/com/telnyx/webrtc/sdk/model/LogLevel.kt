package com.telnyx.webrtc.sdk.model

enum class LogLevel(var priority: Int?) {
    /// Disable logs. SDK logs will not printed. This is the default configuration.
    NONE(8),
    /// Print `error` logs only
    ERROR(6),
    /// Print `warning` logs only
    WARNING(5),
    /// Print `debug` logs only
    DEBUG(3),
    /// Print `info` logs only
    INFO(4),
    /// Print `verto` messages. Incoming and outgoing verto messages are printed.
    VERTO(9),
    /// All the SDK logs are printed.
    ALL(null)

}