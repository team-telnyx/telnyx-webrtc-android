package org.telnyx.webrtc.compose_app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.common.TelnyxViewModel
import org.telnyx.webrtc.compose_app.ui.components.CallQualityDisplay

/**
 * A demo screen showing how to use the WebRTC stats exposure feature.
 * 
 * This screen demonstrates:
 * 1. How to enable debug mode when making or accepting calls
 * 2. How to display real-time call quality metrics
 * 
 * @param telnyxViewModel The TelnyxViewModel instance.
 */
@Composable
fun CallQualityDemoScreen(
    telnyxViewModel: TelnyxViewModel
) {
    val context = LocalContext.current
    
    // State for debug mode toggle
    var debugModeEnabled by remember { mutableStateOf(true) }
    
    // Collect call quality metrics
    val callQualityMetrics by telnyxViewModel.callQualityMetrics.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WebRTC Stats Demo") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Debug mode toggle
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Text(
                    text = "Debug Mode",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Text(
                    text = "Enable to collect real-time call quality metrics",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Switch(
                    checked = debugModeEnabled,
                    onCheckedChange = { debugModeEnabled = it }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Make call button
            Button(
                onClick = {
                    // Example of making a call with debug mode
                    telnyxViewModel.sendInvite(
                        viewContext = context,
                        callerName = "Demo User",
                        callerNumber = "+1234567890",
                        destinationNumber = "+1987654321",
                        clientState = "demo-state",
                        debug = debugModeEnabled
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Make Call with Debug = $debugModeEnabled")
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Display call quality metrics if available
            callQualityMetrics?.let { metrics ->
                CallQualityDisplay(metrics = metrics)
            } ?: run {
                Text(
                    text = "No call quality metrics available yet. Make a call with debug mode enabled to see metrics.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}