/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

/**
 *
 * Enum class to represent the different AudioDevices that can be used by the SDK
 * with a given [code]
 *
 * @param code is the numerical representation of the AudioDevice which coincides AudioDeviceInfo class, eg. 7 -> BLUETOOTH
 *
 * @property BLUETOOTH a connected bluetooth device
 * @property PHONE_EARPIECE the device's default earpiece
 * @property LOUDSPEAKER the loudspeakers on the device
 */
enum class AudioDevice(var code: Int) {
    BLUETOOTH(7),
    PHONE_EARPIECE(0),
    LOUDSPEAKER(99),
}