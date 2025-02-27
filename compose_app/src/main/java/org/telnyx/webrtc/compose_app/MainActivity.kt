package org.telnyx.webrtc.compose_app

import android.content.Intent
import android.os.Bundle
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
import androidx.navigation.compose.rememberNavController
import com.google.firebase.FirebaseApp
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.NotificationsService
import org.telnyx.webrtc.compose_app.ui.screens.HomeScreen
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val viewModel: TelnyxViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        viewModel.initProfile(this)

        handleCallNotification(intent)

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
            val txPushMetaData = intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)
            Timber.d("Action: $action  ${txPushMetaData ?: "No Metadata"}")
            if (action == MyFirebaseMessagingService.ACT_ANSWER_CALL) {
                viewModel.answerIncomingPushCall(this, txPushMetaData)
            } else if (action == MyFirebaseMessagingService.ACT_REJECT_CALL) {
                viewModel.rejectIncomingPushCall(this, txPushMetaData)
            }
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
