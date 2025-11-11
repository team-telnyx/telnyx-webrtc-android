package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.telnyx.webrtc.sdk.model.AudioConstraints

/**
 * Dialog for configuring audio processing constraints.
 *
 * @param isVisible Whether the dialog is visible
 * @param currentConstraints Current audio constraints (null means use defaults)
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when constraints are confirmed with the selected constraints
 */
@Composable
fun AudioConstraintsDialog(
    isVisible: Boolean,
    currentConstraints: AudioConstraints?,
    onDismiss: () -> Unit,
    onConfirm: (AudioConstraints) -> Unit
) {
    if (!isVisible) return

    // Initialize state with current constraints or defaults (all enabled)
    val defaults = currentConstraints ?: AudioConstraints()
    var echoCancellation by remember(currentConstraints) { mutableStateOf(defaults.echoCancellation) }
    var noiseSuppression by remember(currentConstraints) { mutableStateOf(defaults.noiseSuppression) }
    var autoGainControl by remember(currentConstraints) { mutableStateOf(defaults.autoGainControl) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Title
                Text(
                    text = "Audio Processing Constraints",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Echo Cancellation
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = echoCancellation,
                        onCheckedChange = { echoCancellation = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Echo Cancellation",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Removes acoustic echo from speaker feedback",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Noise Suppression
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = noiseSuppression,
                        onCheckedChange = { noiseSuppression = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Noise Suppression",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Reduces background noise for clearer voice",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Auto Gain Control
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = autoGainControl,
                        onCheckedChange = { autoGainControl = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "Auto Gain Control",
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Automatically adjusts microphone volume",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onConfirm(
                                AudioConstraints(
                                    echoCancellation = echoCancellation,
                                    noiseSuppression = noiseSuppression,
                                    autoGainControl = autoGainControl
                                )
                            )
                        }
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
