package org.telnyx.webrtc.xml_app

import android.app.Application

class App : Application() {

    companion object {
        var instance: App? = null
    }

    var crashListener: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            crashListener?.invoke()
            defaultHandler?.uncaughtException(thread, exception)
        }

        instance = this
    }
}
