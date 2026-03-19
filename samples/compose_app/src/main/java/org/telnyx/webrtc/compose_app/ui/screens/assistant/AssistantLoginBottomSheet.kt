package org.telnyx.webrtc.compose_app.ui.screens.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.common.TelnyxViewModel
import org.telnyx.webrtc.compose_app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantLoginBottomSheet(
    telnyxViewModel: TelnyxViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var targetId by remember { mutableStateOf("") }
    var conversationId by remember { mutableStateOf("") }
    val isLoading by telnyxViewModel.isLoading.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.assistant_login),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = stringResource(R.string.assistant_target_id_hint),
                style = MaterialTheme.typography.labelMedium,
                color = Color.Black,
                modifier = Modifier.padding(bottom = 13.dp)
            )

            OutlinedTextField(
                value = targetId,
                onValueChange = { targetId = it },
                label = { Text(stringResource(R.string.assistant_target_id)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Next
                ),
                enabled = !isLoading
            )

            OutlinedTextField(
                value = conversationId,
                onValueChange = { conversationId = it },
                label = { Text(stringResource(R.string.assistant_conversation_id)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (targetId.isNotBlank()) {
                            telnyxViewModel.anonymousLogin(
                                viewContext = context,
                                targetId = targetId.trim(),
                                conversationId = conversationId.trim().ifBlank { null }
                            )
                            onDismiss()
                        }
                    }
                ),
                enabled = !isLoading
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading
                ) {
                    Text(stringResource(R.string.Cancel))
                }

                Button(
                    onClick = {
                        if (targetId.isNotBlank()) {
                            telnyxViewModel.anonymousLogin(
                                viewContext = context,
                                targetId = targetId.trim(),
                                conversationId = conversationId.trim().ifBlank { null }
                            )
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isLoading && targetId.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(stringResource(R.string.assistant_login_button))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}