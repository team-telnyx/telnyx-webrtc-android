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
        handleCallNotification(intent)
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        viewModel.disconnect(this)
    }

    private fun handleCallNotification(intent: Intent?) {

        if (intent == null) {
            return
        }

        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            putExtra("action", NotificationsService.STOP_ACTION)
        }
        serviceIntent.setAction(NotificationsService.STOP_ACTION)
        startService(serviceIntent)

        val action = intent.extras?.getString(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)

        action?.let {
            val txPushMetaData =
                intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)
            Timber.d("Action: $action  ${txPushMetaData ?: "No Metadata"}")
            when (action) {
                MyFirebaseMessagingService.ACT_ANSWER_CALL -> {
                    viewModel.answerIncomingPushCall(this, txPushMetaData)
                }
                MyFirebaseMessagingService.ACT_REJECT_CALL -> {
                    viewModel.rejectIncomingPushCall(this, txPushMetaData)
                }
                MyFirebaseMessagingService.ACT_OPEN_TO_REPLY -> {
                    viewModel.connectWithLastUsedConfig(this, txPushMetaData)
                }
            }
        }
    }

    private fun checkPermission() {
        // Create a mutable list of permissions that will always be needed.
        val permissions = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO
        )

        // Conditionally add permissions based on the API level.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 34) { // Only available on Android 14 and above.
            permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        // Now use Dexter to check the permissions.
        Dexter.withContext(this)
            .withPermissions(*permissions.toTypedArray())
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    report?.let {
                        if (report.areAllPermissionsGranted()) {
                            // All permissions are granted.
                        } else {
                            // Some permissions are denied.
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

