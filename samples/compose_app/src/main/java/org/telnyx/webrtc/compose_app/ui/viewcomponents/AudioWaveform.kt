package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.max

@Composable
fun AudioWaveform(
    audioLevels: List<Float>,
    modifier: Modifier = Modifier,
    barColor: Color,
    minBarHeight: Float = 2f,
    maxBarHeight: Float = 50f
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Use the actual list size for the number of bars
        val barCount = audioLevels.size
        if (barCount > 0) {
            audioLevels.forEachIndexed { _, level ->
                // Ensure level is within 0.0 to 1.0
                val clampedLevel = level.coerceIn(0f, 1f)

                val height by animateFloatAsState(
                    targetValue = minBarHeight + (clampedLevel * (maxBarHeight - minBarHeight)),
                    animationSpec = tween(durationMillis = 100),
                    label = "waveformBarHeightAnimation"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(max(minBarHeight, height).dp) // Ensure minimum height
                        .padding(horizontal = 1.dp)
                        .background(barColor, RoundedCornerShape(2.dp))
                )
            }
        } else {
            Spacer(modifier = Modifier.height(minBarHeight.dp))
        }
    }
} 