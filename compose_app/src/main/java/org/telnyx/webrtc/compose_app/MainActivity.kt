package org.telnyx.webrtc.compose_app

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.google.firebase.FirebaseApp
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.NotificationsService
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.ui.screens.HomeScreen
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import timber.log.Timber

class MainActivity : ComponentActivity(), DefaultLifecycleObserver {

    private val viewModel: TelnyxViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super<ComponentActivity>.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        lifecycle.addObserver(this)

        lifecycleScope.launch {
            viewModel.initProfile(this@MainActivity)
            checkPermission()
            handleCallNotification(intent)
        }

        enableEdgeToEdge()
        setContent {
            TelnyxAndroidWebRTCSDKTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    innerPadding.calculateTopPadding()
                    HomeScreen(rememberNavController(), telnyxViewModel = viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Timber.d("onNewIntent called with action: ${intent.action}")
        setIntent(intent) // Update the stored intent
        handleCallNotification(intent)
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        viewModel.disconnect(this)
    }

    private fun handleCallNotification(intent: Intent?) {
        if (intent == null) {
            Timber.d("handleCallNotification: intent is null")
            return
        }

        // Stop the notification service
        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            action = NotificationsService.STOP_ACTION
        }
        try {
            startService(serviceIntent)
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop NotificationsService")
        }

        // Get the action and metadata from the intent
        val action = intent.extras?.getString(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)
        val txPushMetaData = intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)

        Timber.d("handleCallNotification: action=$action, metadata=${txPushMetaData != null}")

        if (action != null && txPushMetaData != null) {
            try {
                when (action) {
                    MyFirebaseMessagingService.ACT_ANSWER_CALL -> {
                        Timber.d("Answering call from notification")
                        viewModel.answerIncomingPushCall(this, txPushMetaData)
                    }
                    MyFirebaseMessagingService.ACT_REJECT_CALL -> {
                        Timber.d("Rejecting call from notification")
                        viewModel.rejectIncomingPushCall(this, txPushMetaData)
                    }
                    else -> {
                        Timber.d("Unknown action: $action")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error handling call notification")
            }
        } else {
            Timber.d("No action or metadata in intent")
        }
    }

    private fun checkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dexter.withContext(this)
                .withPermissions(
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        report?.let {
                            if (report.areAllPermissionsGranted()) {
                                // All permissions are granted
                            } else {
                                // Some permissions are denied
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.notification_permission_text),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permissions: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        }
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    TelnyxAndroidWebRTCSDKTheme {
        Greeting("Android")
    }
}
