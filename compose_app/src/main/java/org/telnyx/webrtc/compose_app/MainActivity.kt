package org.telnyx.webrtc.compose_app

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
import org.telnyx.webrtc.compose_app.ui.screens.HomeScreen
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme

class MainActivity : ComponentActivity() {

    private val viewModel: TelnyxViewModel by viewModels()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)

        viewModel.initProfile(this)

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