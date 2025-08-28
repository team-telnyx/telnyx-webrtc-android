package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.SocketConnectionQuality

/**
 * A composable that displays the current connection quality as a visual indicator.
 *
 * @param connectionMetrics The current connection metrics, or null if not available
 * @param modifier Modifier to be applied to the indicator
 * @param showDetails Whether to show detailed metrics (interval, jitter)
 */
@Composable
fun ConnectionQualityIndicator(
    connectionMetrics: SocketConnectionMetrics?,
    modifier: Modifier = Modifier,
    showDetails: Boolean = false
) {
    val quality = connectionMetrics?.quality ?: SocketConnectionQuality.CALCULATING
    
    // Animate color changes
    val indicatorColor by animateColorAsState(
        targetValue = when (quality) {
            SocketConnectionQuality.CALCULATING -> Color(0xFF9E9E9E) // Gray
            SocketConnectionQuality.EXCELLENT -> Color(0xFF4CAF50)   // Green
            SocketConnectionQuality.GOOD -> Color(0xFF8BC34A)        // Light Green
            SocketConnectionQuality.FAIR -> Color(0xFFFFC107)        // Amber
            SocketConnectionQuality.POOR -> Color(0xFFF44336)        // Red
        },
        animationSpec = tween(durationMillis = 300),
        label = "Connection Quality Color"
    )
    
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Connection quality dot
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            
            // Quality text
            Text(
                text = when (quality) {
                    SocketConnectionQuality.CALCULATING -> "Calculating..."
                    else -> quality.name
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            // Show details if requested
            if (showDetails && connectionMetrics != null) {
                HorizontalDivider(
                    modifier = Modifier
                        .height(16.dp)
                        .width(1.dp),
                    thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                )

                // Interval
                connectionMetrics.averageIntervalMs?.let { interval ->
                    Text(
                        text = "${interval / 1000}s",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                // Jitter
                connectionMetrics.jitterMs?.let { jitter ->
                    Text(
                        text = "±${jitter}ms",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * A minimal connection quality dot indicator.
 *
 * @param connectionMetrics The current connection metrics, or null if not available
 * @param modifier Modifier to be applied to the indicator
 */
@Composable
fun ConnectionQualityDot(
    connectionMetrics: SocketConnectionMetrics?,
    modifier: Modifier = Modifier
) {
    val quality = connectionMetrics?.quality ?: SocketConnectionQuality.CALCULATING
    
    val indicatorColor by animateColorAsState(
        targetValue = when (quality) {
            SocketConnectionQuality.CALCULATING -> Color(0xFF9E9E9E) // Gray
            SocketConnectionQuality.EXCELLENT -> Color(0xFF4CAF50)   // Green
            SocketConnectionQuality.GOOD -> Color(0xFF8BC34A)        // Light Green
            SocketConnectionQuality.FAIR -> Color(0xFFFFC107)        // Amber
            SocketConnectionQuality.POOR -> Color(0xFFF44336)        // Red
        },
        animationSpec = tween(durationMillis = 300),
        label = "Connection Dot Color"
    )
    
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(indicatorColor)
    )
}

/**
 * A detailed connection metrics display showing all available metrics.
 *
 * @param connectionMetrics The current connection metrics, or null if not available
 * @param modifier Modifier to be applied to the display
 */
@Composable
fun ConnectionMetricsDetail(
    connectionMetrics: SocketConnectionMetrics?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Connection Quality",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            if (connectionMetrics != null) {
                // Quality indicator
                ConnectionQualityIndicator(
                    connectionMetrics = connectionMetrics,
                    showDetails = false
                )

                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    thickness = DividerDefaults.Thickness, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                )

                // Detailed metrics
                ConnectionMetricRow(label = "Ping Interval", value = connectionMetrics.intervalMs?.let { "${it}ms" } ?: "N/A")
                ConnectionMetricRow(label = "Average Interval", value = connectionMetrics.averageIntervalMs?.let { "${it}ms" } ?: "N/A")
                ConnectionMetricRow(label = "Jitter", value = connectionMetrics.jitterMs?.let { "${it}ms" } ?: "N/A")
                ConnectionMetricRow(label = "Min Interval", value = connectionMetrics.minIntervalMs?.let { "${it}ms" } ?: "N/A")
                ConnectionMetricRow(label = "Max Interval", value = connectionMetrics.maxIntervalMs?.let { "${it}ms" } ?: "N/A")
                
                // Success rate
                val successRate = connectionMetrics.getSuccessRate()
                ConnectionMetricRow(
                    label = "Success Rate",
                    value = String.format("%.1f%%", successRate),
                    valueColor = when {
                        successRate >= 99 -> Color(0xFF4CAF50)
                        successRate >= 95 -> Color(0xFF8BC34A)
                        successRate >= 90 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
                
                // Ping statistics
                ConnectionMetricRow(label = "Total Pings", value = connectionMetrics.totalPings.toString())
                if (connectionMetrics.missedPings > 0) {
                    ConnectionMetricRow(
                        label = "Missed Pings",
                        value = connectionMetrics.missedPings.toString(),
                        valueColor = Color(0xFFF44336)
                    )
                }
            } else {
                Text(
                    text = "No connection metrics available",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun ConnectionMetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}