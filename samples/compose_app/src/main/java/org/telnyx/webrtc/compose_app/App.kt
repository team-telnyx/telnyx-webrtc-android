package org.telnyx.webrtc.compose_app

import android.app.Application
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import timber.log.Timber

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG){
            Timber.plant(Timber.DebugTree())
        }
    }
}