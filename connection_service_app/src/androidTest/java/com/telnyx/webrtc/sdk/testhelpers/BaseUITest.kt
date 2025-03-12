package com.telnyx.webrtc.sdk.testhelpers

import android.Manifest
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule

abstract class BaseUITest : BaseTestInstrumentation() {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.INTERNET,
        Manifest.permission.ACCESS_NETWORK_STATE,
        Manifest.permission.RECORD_AUDIO,
    )
}
