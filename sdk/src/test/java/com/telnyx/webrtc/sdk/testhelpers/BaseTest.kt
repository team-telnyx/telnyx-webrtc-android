package com.telnyx.webrtc.sdk.testhelpers

import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterAll
import timber.log.Timber

abstract class BaseTest {

    init {
        Timber.uprootAll()
        Timber.plant(JUnitTree())
        testClassName = this.javaClass.simpleName
    }

    companion object {
        private var testClassName: String? = null

        @JvmStatic
        @AfterAll
        fun onTestClassFinished() {
            unmockkAll()
            Timber.tag(testClassName).v("onTestClassFinished()")
            Timber.uprootAll()
        }
    }
}
