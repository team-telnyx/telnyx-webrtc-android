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
import androidx.compose.ui.res.stringResource
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.SocketConnectionQuality
import org.telnyx.webrtc.compose_app.R

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
    val quality = connectionMetrics?.quality ?: SocketConnectionQuality.DISCONNECTED
    
    // Animate color changes
    val indicatorColor by animateColorAsState(
        targetValue = when (quality) {
            SocketConnectionQuality.DISCONNECTED -> Color(0xFF616161)  // Dark Gray
            SocketConnectionQuality.CALCULATING -> Color(0xFF9E9E9E)   // Gray
            SocketConnectionQuality.EXCELLENT -> Color(0xFF4CAF50)     // Green
            SocketConnectionQuality.GOOD -> Color(0xFF8BC34A)          // Light Green
            SocketConnectionQuality.FAIR -> Color(0xFFFFC107)          // Amber
            SocketConnectionQuality.POOR -> Color(0xFFF44336)          // Red
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
                    SocketConnectionQuality.DISCONNECTED -> stringResource(R.string.connection_quality_disconnected)
                    SocketConnectionQuality.CALCULATING -> stringResource(R.string.connection_quality_calculating)
                    SocketConnectionQuality.EXCELLENT -> stringResource(R.string.connection_quality_excellent)
                    SocketConnectionQuality.GOOD -> stringResource(R.string.connection_quality_good)
                    SocketConnectionQuality.FAIR -> stringResource(R.string.connection_quality_fair)
                    SocketConnectionQuality.POOR -> stringResource(R.string.connection_quality_poor)
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
                        text = stringResource(R.string.connection_metrics_seconds, interval / 1000),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                
                // Jitter
                connectionMetrics.jitterMs?.let { jitter ->
                    Text(
                        text = "±" + stringResource(R.string.connection_metrics_ms, jitter),
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
    val quality = connectionMetrics?.quality ?: SocketConnectionQuality.DISCONNECTED
    
    val indicatorColor by animateColorAsState(
        targetValue = when (quality) {
            SocketConnectionQuality.DISCONNECTED -> Color(0xFF616161)  // Dark Gray
            SocketConnectionQuality.CALCULATING -> Color(0xFF9E9E9E)   // Gray
            SocketConnectionQuality.EXCELLENT -> Color(0xFF4CAF50)     // Green
            SocketConnectionQuality.GOOD -> Color(0xFF8BC34A)          // Light Green
            SocketConnectionQuality.FAIR -> Color(0xFFFFC107)          // Amber
            SocketConnectionQuality.POOR -> Color(0xFFF44336)          // Red
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
            containerColor = MaterialTheme.colorScheme.surface
        ),
    ) {
        Column(
            modifier = Modifier.padding(2.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.connection_quality),
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
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_interval), value = connectionMetrics.intervalMs?.let { stringResource(R.string.connection_metrics_ms, it) } ?: stringResource(R.string.connection_metrics_not_available))
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_average_interval), value = connectionMetrics.averageIntervalMs?.let { stringResource(R.string.connection_metrics_ms, it) } ?: stringResource(R.string.connection_metrics_not_available))
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_jitter), value = connectionMetrics.jitterMs?.let { stringResource(R.string.connection_metrics_ms, it) } ?: stringResource(R.string.connection_metrics_not_available))
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_min_interval), value = connectionMetrics.minIntervalMs?.let { stringResource(R.string.connection_metrics_ms, it) } ?: stringResource(R.string.connection_metrics_not_available))
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_max_interval), value = connectionMetrics.maxIntervalMs?.let { stringResource(R.string.connection_metrics_ms, it) } ?: stringResource(R.string.connection_metrics_not_available))
                
                // Success rate
                val successRate = connectionMetrics.getSuccessRate()
                ConnectionMetricRow(
                    label = stringResource(R.string.connection_metrics_success_rate),
                    value = stringResource(R.string.connection_metrics_percent, successRate),
                    valueColor = when {
                        successRate >= 99 -> Color(0xFF4CAF50)
                        successRate >= 95 -> Color(0xFF8BC34A)
                        successRate >= 90 -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
                
                // Ping statistics
                ConnectionMetricRow(label = stringResource(R.string.connection_metrics_total_pings), value = connectionMetrics.totalPings.toString())
                if (connectionMetrics.missedPings > 0) {
                    ConnectionMetricRow(
                        label = stringResource(R.string.connection_metrics_missed_pings),
                        value = connectionMetrics.missedPings.toString(),
                        valueColor = Color(0xFFF44336)
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.no_connection_metrics),
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