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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.burnoutcrew.reorderable.*
import org.telnyx.webrtc.compose_app.R

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
        mutableStateOf(selectedCodecs.toList()) 
    }
    
    var selectedTabIndex by remember { mutableStateOf(0) }
    val tabs = listOf(stringResource(R.string.codec_available_tab), stringResource(R.string.codec_selected_tab))
    
    // Create unique keys for codecs by combining mimeType and clockRate
    fun codecKey(codec: AudioCodec): String = "${codec.mimeType}_${codec.clockRate}_${codec.channels}"
    
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
                .fillMaxWidth(0.98f)
                .fillMaxHeight(0.85f)
                .widthIn(min = 400.dp, max = 600.dp)
                .padding(4.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.preferred_audio_codecs),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                // Tab Row
                TabRow(
                    selectedTabIndex = selectedTabIndex,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTabIndex == index,
                            onClick = { selectedTabIndex = index },
                            text = { 
                                Text(
                                    text = if (index == 1 && currentSelection.isNotEmpty()) {
                                        stringResource(R.string.codec_selected_tab_count, currentSelection.size)
                                    } else {
                                        title
                                    }
                                )
                            }
                        )
                    }
                }

                // Tab Content
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (selectedTabIndex) {
                        0 -> {
                            // Available Codecs Tab
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = stringResource(R.string.select_codecs_to_use),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(
                                        items = availableCodecs,
                                        key = { _, codec -> codecKey(codec) }
                                    ) { _, codec ->
                                        val isSelected = currentSelection.contains(codec)
                                        
                                        CodecItem(
                                            codec = codec,
                                            isSelected = isSelected,
                                            onClick = {
                                                currentSelection = if (isSelected) {
                                                    currentSelection.filter { it != codec }
                                                } else {
                                                    currentSelection + codec
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                        1 -> {
                            // Selected Codecs Tab (Reorderable)
                            Column(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Text(
                                    text = if (currentSelection.isEmpty()) {
                                        stringResource(R.string.no_codecs_selected_instruction)
                                    } else {
                                        stringResource(R.string.drag_to_reorder_priority)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                                
                                if (currentSelection.isNotEmpty()) {
                                    LazyColumn(
                                        state = reorderableState.listState,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                            .padding(12.dp)
                                            .reorderable(reorderableState),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        itemsIndexed(
                                            items = currentSelection,
                                            key = { _, codec -> codecKey(codec) }
                                        ) { index, codec ->
                                            ReorderableItem(reorderableState, key = codecKey(codec)) { isDragging ->
                                                SelectedCodecItem(
                                                    codec = codec,
                                                    index = index + 1,
                                                    isDragging = isDragging,
                                                    reorderableState = reorderableState,
                                                    onRemove = {
                                                        currentSelection = currentSelection.filter { it != codec }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .weight(1f)
                                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.no_codecs_selected),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Clear All button (left side)
                    TextButton(
                        onClick = { 
                            currentSelection = emptyList()
                            selectedTabIndex = 0 // Switch to Available tab
                        },
                        enabled = currentSelection.isNotEmpty()
                    ) {
                        Text(stringResource(R.string.clear_all))
                    }
                    
                    // Cancel and Save buttons (right side)
                    Row {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.Cancel))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { onConfirm(currentSelection) }
                        ) {
                            Text(stringResource(R.string.save))
                        }
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
                text = stringResource(R.string.codec_format, codec.clockRate, codec.channels),
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
    reorderableState: ReorderableLazyListState,
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
            imageVector = Icons.Default.Menu,
            contentDescription = stringResource(R.string.drag_to_reorder),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.detectReorderAfterLongPress(reorderableState)
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
                text = stringResource(R.string.codec_format, codec.clockRate, codec.channels),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.remove_codec),
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}