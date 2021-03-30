package com.telnyx.webrtc.sdk.model

enum class LogLevel {
    /// Disable logs. SDK logs will not printed. This is the default configuration.
    NONE,
    /// Print `error` logs only
    ERROR,
    /// Print `warning` logs only
    WARNING,
    /// Print `success` logs only
    SUCCESS,
    /// Print `info` logs only
    INFO,
    /// Print `verto` messages. Incoming and outgoing verto messages are printed.
    VERTO,
    /// All the SDK logs are printed.
    ALL
}