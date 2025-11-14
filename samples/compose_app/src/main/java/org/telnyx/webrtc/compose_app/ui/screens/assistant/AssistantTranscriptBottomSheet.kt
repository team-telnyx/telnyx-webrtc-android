package org.telnyx.webrtc.compose_app.ui.screens.assistant

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.TranscriptItem
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.utils.Utils
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
    var showImagePicker by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var selectedImagesBase64 by remember { mutableStateOf<List<String>>(emptyList()) }

    val sendMessage = {
        if (messageText.isNotBlank() || selectedImagesBase64.isNotEmpty()) {
            telnyxViewModel.sendAIAssistantMessage(
                context,
                message = messageText.trim(),
                imagesUrls = selectedImagesBase64.ifEmpty { null }
            )
            messageText = ""
            selectedImagesBase64 = emptyList()

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

            // Images preview (if selected)
            if (selectedImagesBase64.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(selectedImagesBase64.size) { index ->
                        val base64Image = selectedImagesBase64[index]
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                        ) {
                            Card(
                                modifier = Modifier.fillMaxSize(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                val imagePreview = remember(base64Image) { Utils.base64ToBitmap(base64Image) }
                                imagePreview?.let {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(it)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Selected image preview",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            // Close button to remove image
                            IconButton(
                                onClick = {
                                    selectedImagesBase64 = selectedImagesBase64.filterIndexed { i, _ -> i != index }
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                                    .padding(2.dp)
                            ) {
                                Surface(
                                    modifier = Modifier.size(20.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    color = Color.Black.copy(alpha = 0.6f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove image",
                                        tint = Color.White,
                                        modifier = Modifier.padding(2.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Message input
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = { showImagePicker = true }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_image),
                        contentDescription = stringResource(R.string.assistant_add_image)
                    )
                }

                IconButton(
                    onClick = { showCamera = true }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_photo),
                        contentDescription = "Take photo"
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

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
                    enabled = messageText.isNotBlank() || selectedImagesBase64.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = stringResource(R.string.assistant_send)
                    )
                }
            }
        }
    }

    // Show image picker when triggered
    if (showImagePicker) {
        ImagePickerDialog(
            onImageSelected = { base64Image ->
                base64Image?.let {
                    selectedImagesBase64 = selectedImagesBase64 + it
                }
                showImagePicker = false
            },
            onDismiss = {
                showImagePicker = false
            }
        )
    }

    // Show camera when triggered
    if (showCamera) {
        CameraDialog(
            onPhotoTaken = { base64Image ->
                base64Image?.let {
                    selectedImagesBase64 = selectedImagesBase64 + it
                }
                showCamera = false
            },
            onDismiss = {
                showCamera = false
            }
        )
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

                // Display images in horizontal row if present
                item.images?.let { imagesList ->
                    if (imagesList.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(imagesList.size) { index ->
                                val imageUrl = imagesList[index]
                                val imagePreview = remember(imageUrl) { Utils.base64ToBitmap(imageUrl) }
                                imagePreview?.let {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(it)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = "Image attached",
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }
                }

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

@Composable
private fun ImagePickerDialog(
    onImageSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            // Convert URI to base64 string
            val base64Image = Utils.uriToBase64(context, selectedUri)
            onImageSelected(base64Image)
        } ?: onDismiss()
    }

    LaunchedEffect(Unit) {
        launcher.launch("image/*")
    }
}

@Composable
private fun CameraDialog(
    onPhotoTaken: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val photoUri = remember {
        createImageFileUri(context)
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            // Convert the captured photo to base64
            val base64Image = Utils.uriToBase64(context, photoUri)
            onPhotoTaken(base64Image)
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        photoUri?.let { launcher.launch(it) } ?: onDismiss()
    }
}

private fun createImageFileUri(context: Context): Uri? {
    return try {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "new_photo_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

