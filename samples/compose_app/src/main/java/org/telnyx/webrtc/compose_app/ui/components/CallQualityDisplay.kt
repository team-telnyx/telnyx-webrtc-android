package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics

/**
 * A composable that displays call quality metrics.
 *
 * @param metrics The call quality metrics to display.
 * @param modifier Optional modifier for the component.
 */
@Composable
fun CallQualityDisplay(
    metrics: CallQualityMetrics?,
    modifier: Modifier = Modifier
) {
    if (metrics == null) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Show detailed metrics once quality is known
            Text(
                text = "Call Quality Metrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (metrics.quality == CallQuality.UNKNOWN) {
                // Show loading indicator if quality is unknown
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.width(24.dp)) // Adjust size as needed
                }
            } else {

                // Quality indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Quality:",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    QualityIndicator(quality = metrics.quality)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // MOS score
                MetricRow(
                    label = "MOS Score:",
                    value = String.format("%.2f", metrics.mos)
                )

                // Jitter
                MetricRow(
                    label = "Jitter:",
                    value = String.format("%.2f ms", metrics.jitter * 1000)
                )

                // Round-trip time
                MetricRow(
                    label = "Round-trip Time:",
                    value = String.format("%.2f ms", metrics.rtt * 1000)
                )
            }
        }
    }
}

/**
 * A row displaying a metric label and value.
 *
 * @param label The label for the metric.
 * @param value The value of the metric.
 * @param modifier Optional modifier for the component.
 */
@Composable
private fun MetricRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * A visual indicator of call quality.
 *
 * @param quality The call quality to display.
 * @param modifier Optional modifier for the component.
 */
@Composable
private fun QualityIndicator(
    quality: CallQuality,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (quality) {
        CallQuality.EXCELLENT -> Color(0xFF4CAF50) to "Excellent"
        CallQuality.GOOD -> Color(0xFF8BC34A) to "Good"
        CallQuality.FAIR -> Color(0xFFFFC107) to "Fair"
        CallQuality.POOR -> Color(0xFFFF9800) to "Poor"
        CallQuality.BAD -> Color(0xFFF44336) to "Bad"
        CallQuality.UNKNOWN -> Color(0xFF9E9E9E) to "Unknown"
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        // Color indicator
        Spacer(
            modifier = Modifier
                .width(16.dp)
                .height(16.dp)
                .background(color, RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.width(8.dp))

        // Quality text
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}