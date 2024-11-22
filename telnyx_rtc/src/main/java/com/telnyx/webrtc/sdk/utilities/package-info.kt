/**
 * # Utilities Package
 *
 * Contains utility classes and extension functions used throughout the SDK.
 *
 * ## Key Components
 *
 * ### Network Utilities
 * - [ConnectivityHelper]: Manages network connectivity state and callbacks
 *
 * ### String Extensions
 * - String encoding/decoding utilities
 * - JSON conversion utilities
 *
 * ### Logging
 * - [TelnyxLoggingTree]: Custom Timber tree for SDK logging
 *
 * ## Features
 * - Network state monitoring
 * - Base64 encoding/decoding
 * - JSON serialization/deserialization
 * - Configurable logging levels
 *
 * ## Usage Example
 * ```kotlin
 * val encoded = myString.encodeBase64()
 * val json = myObject.toJsonString()
 * ConnectivityHelper.isNetworkEnabled(context)
 * ```
 *
 * @see com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
 * @see com.telnyx.webrtc.sdk.utilities.TelnyxLoggingTree
 */
package com.telnyx.webrtc.sdk.utilities