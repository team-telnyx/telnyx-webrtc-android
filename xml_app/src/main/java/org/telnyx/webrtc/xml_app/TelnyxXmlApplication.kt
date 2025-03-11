package org.telnyx.webrtc.xml_app

import android.app.Application
import com.google.firebase.FirebaseApp

class TelnyxXmlApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}