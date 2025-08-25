package org.telnyx.webrtc.compose_app.ui.screens.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.TranscriptItem
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantTranscriptBottomSheet(
    telnyxViewModel: TelnyxViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var messageText by remember { mutableStateOf("") }
    val transcriptItems by telnyxViewModel.transcriptMessages?.collectAsState(initial = emptyList()) ?: remember { mutableStateOf(emptyList<TranscriptItem>()) }
    val listState = rememberLazyListState()

    val sendMessage = {
        if (messageText.isNotBlank()) {
            telnyxViewModel.sendAIAssistantMessage(
                context,
                message = messageText.trim()
            )
            messageText = ""
            
            // Scroll to bottom after message is sent
            scope.launch {
                kotlinx.coroutines.delay(100)
                if (transcriptItems.isNotEmpty()) {
                    listState.animateScrollToItem(transcriptItems.size)
                }
            }
        }
    }

    ModalBottomSheet(
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = {
            onDismiss.invoke()
        },
        containerColor = Color.White,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(Dimens.mediumSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MediumTextBold(
                    text = stringResource(R.string.assistant_transcript),
                    modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                )
                IconButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            onDismiss.invoke()
                        }
                    }
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(R.string.close_button_dessc),
                        modifier = Modifier.size(Dimens.size16dp)
                    )
                }
            }

            // Transcript list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(transcriptItems.size) { index ->
                    val item = transcriptItems[index]
                    TranscriptItemComposable(item = item)
                }
            }

            // Message input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    placeholder = { Text(stringResource(R.string.assistant_message_hint)) },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(
                        onSend = { sendMessage() },
                        onDone = { sendMessage() }
                    ),
                    maxLines = 3
                )

                Spacer(modifier = Modifier.width(8.dp))

                IconButton(
                    onClick = { sendMessage() },
                    enabled = messageText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.assistant_send)
                    )
                }
            }
        }
    }
    
    // Auto-scroll to bottom when new items are added
    LaunchedEffect(transcriptItems.size) {
        if (transcriptItems.isNotEmpty()) {
            listState.animateScrollToItem(transcriptItems.size - 1)
        }
    }
}

@Composable
private fun TranscriptItemComposable(item: TranscriptItem) {
    val isUser = item.role == TranscriptItem.ROLE_USER
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.secondary.copy(alpha = 0.1f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 280.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = if (isUser) stringResource(R.string.assistant_you) else stringResource(R.string.assistant_ai),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(item.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
