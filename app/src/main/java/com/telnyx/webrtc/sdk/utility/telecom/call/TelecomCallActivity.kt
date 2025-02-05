package com.telnyx.webrtc.sdk.utility.telecom.call

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.util.Log
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.getSystemService
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallRepository

/**
 * This activity is used to launch the incoming or ongoing call. It uses special flags to be able
 * to be launched in the lockscreen and as a full-screen notification.
 */
@RequiresApi(Build.VERSION_CODES.O)
class TelecomCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The repo contains all the call logic and communication with the Telecom SDK.
        val repository =
            TelecomCallRepository.instance ?: TelecomCallRepository.create(applicationContext)

        // Set the right flags for a call type activity.
        setupCallActivity()

        setContent {
            MaterialTheme {
                Surface(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                ) {
                    // Show the in-call screen
                    TelecomCallScreen(repository) {
                        // If we receive that the called finished, finish the activity
                        finishAndRemoveTask()
                        Log.d("TelecomCallActivity", "Call finished. Finishing activity")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Force the service to update in case something change like Mic permissions.
        startService(
            Intent(this, TelecomCallService::class.java).apply {
                action = TelecomCallService.ACTION_UPDATE_CALL
            },
        )
    }

    /**
     * Enable the calling activity to be shown in the lockscreen and dismiss the keyguard to enable
     * users to answer without unblocking.
     */
    private fun setupCallActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON,
            )
        }

        val keyguardManager = getSystemService<KeyguardManager>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager != null) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }
}