package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.burnoutcrew.reorderable.*

/**
 * Dialog for selecting and reordering preferred audio codecs.
 *
 * @param isVisible Whether the dialog is visible
 * @param availableCodecs List of all available audio codecs
 * @param selectedCodecs Currently selected codecs in order of preference
 * @param onDismiss Callback when dialog is dismissed
 * @param onConfirm Callback when user confirms selection with new codec list
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodecSelectionDialog(
    isVisible: Boolean,
    availableCodecs: List<AudioCodec>,
    selectedCodecs: List<AudioCodec>,
    onDismiss: () -> Unit,
    onConfirm: (List<AudioCodec>) -> Unit
) {
    if (!isVisible) return

    var currentSelection by remember(selectedCodecs) { 
        mutableStateOf(selectedCodecs.toMutableList()) 
    }
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            currentSelection = currentSelection.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
    )

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Preferred Audio Codecs",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                Text(
                    text = "Select and reorder your preferred codecs. Drag to reorder by priority.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Available codecs section
                Text(
                    text = "Available Codecs:",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(availableCodecs.size) { index ->
                        val codec = availableCodecs[index]
                        val isSelected = currentSelection.contains(codec)
                        
                        CodecItem(
                            codec = codec,
                            isSelected = isSelected,
                            onClick = {
                                currentSelection = if (isSelected) {
                                    currentSelection.toMutableList().apply { remove(codec) }
                                } else {
                                    currentSelection.toMutableList().apply { add(codec) }
                                }
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Selected codecs section (reorderable)
                Text(
                    text = "Selected Codecs (in order of preference):",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    state = reorderableState.listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .padding(8.dp)
                        .reorderable(reorderableState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    itemsIndexed(currentSelection, key = { _, codec -> codec.mimeType }) { index, codec ->
                        ReorderableItem(reorderableState, key = codec.mimeType) { isDragging ->
                            SelectedCodecItem(
                                codec = codec,
                                index = index + 1,
                                isDragging = isDragging,
                                onRemove = {
                                    currentSelection = currentSelection.toMutableList().apply { remove(codec) }
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirm(currentSelection) }
                    ) {
                        Text("Apply")
                    }
                }
            }
        }
    }
}

@Composable
private fun CodecItem(
    codec: AudioCodec,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onClick() }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = codec.mimeType,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${codec.clockRate} Hz, ${codec.channels} ch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SelectedCodecItem(
    codec: AudioCodec,
    index: Int,
    isDragging: Boolean,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (isDragging) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.secondaryContainer
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$index.",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = codec.mimeType,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${codec.clockRate} Hz, ${codec.channels} ch",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove codec",
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}