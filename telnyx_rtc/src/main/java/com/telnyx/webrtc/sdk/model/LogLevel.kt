/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import com.telnyx.webrtc.sdk.utilities.*

/**
 *
 * Enum class to describe the loglevel that the SDK should use. The log level itself is implemented with Timber via [TelnyxLoggingTree]
 * each level has a provided [priority]
 *
 * @see TelnyxLoggingTree
 *
 * @param priority is the log level priority representation as an integer
 *
 * @property NONE  Disable logs. SDK logs will not printed. This is the default configuration.
 * @property ERROR Print `error` logs only
 * @property WARNING Print `warning` logs only
 * @property DEBUG Print `debug` logs only
 * @property INFO Print `info` logs only
 * @property VERTO Print `verto` messages. Incoming and outgoing verto messages are printed.
 * @property ALL All the SDK logs are printed.
 */
enum class LogLevel(var priority: Int?) {
    NONE(8),
    ERROR(6),
    WARNING(5),
    DEBUG(3),
    INFO(4),
    VERTO(9),
    ALL(null)
}
