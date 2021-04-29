/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

//Coincides with AudioDeviceInfo class for AudioManager handling provided by Android
enum class AudioDevice(var code: Int) {
    BLUETOOTH(7),
    PHONE_EARPIECE(0),
    LOUDSPEAKER(99),
}