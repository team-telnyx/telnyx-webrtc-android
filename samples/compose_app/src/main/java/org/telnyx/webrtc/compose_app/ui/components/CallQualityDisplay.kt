package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.secondary_background_color
import org.telnyx.webrtc.compose_app.ui.viewcomponents.AudioWaveform
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.utils.capitalizeFirstChar

/**
 * A composable that displays call quality metrics.
 *
 * @param metrics The call quality metrics to display.
 * @param inboundLevels The inbound audio levels to display.
 * @param outboundLevels The outbound audio levels to display.
 * @param modifier Optional modifier for the component.
 */
@Composable
fun CallQualityDisplay(
    metrics: CallQualityMetrics?,
    inboundLevels: List<Float>,
    outboundLevels: List<Float>,
    modifier: Modifier = Modifier
) {
    if (metrics == null && inboundLevels.isEmpty() && outboundLevels.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacing16dp)
    ) {
        if (metrics?.quality == CallQuality.UNKNOWN) {
            // Show loading indicator if quality is unknown
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp)) // Adjust size as needed
            }
        } else {
            // Jitter
            MetricRow(
                label = stringResource(R.string.call_quality_metrics_jitter),
                value = String.format("%.2f ms", metrics?.jitter?.times(1000) ?: 0.0)
            )

            // MOS score
            MetricRow(
                label = stringResource(R.string.call_quality_metrics_mos),
                value = String.format("%.2f", metrics?.mos ?: 0.0)
            )

            // Quality
            MetricRow(
                label = stringResource(R.string.call_quality_metrics_quality),
                value = metrics?.quality?.name?.capitalizeFirstChar() ?: stringResource(
                    R.string.unknown_label)
            )

            // Round-trip time
            MetricRow(
                label = stringResource(R.string.call_quality_metrics_round_trip_time),
                value = String.format("%.2f ms", metrics?.rtt?.times(1000) ?: 0.0)
            )

            //Inbound audio
            RegularText(text = stringResource(R.string.call_quality_metrics_inbound_audio),
                size = Dimens.textSize16sp,
                fontWeight = FontWeight.SemiBold)

            metrics?.inboundAudio?.forEach { (key, value) ->
                MetricRow(key.capitalizeFirstChar() ?: stringResource(R.string.unknown_label), value.toString())
            }

            //Outbound audio
            RegularText(text = stringResource(R.string.call_quality_metrics_outbound_audio),
                size = Dimens.textSize16sp,
                fontWeight = FontWeight.SemiBold)

            metrics?.outboundAudio?.forEach { (key, value) ->
                MetricRow(key.capitalizeFirstChar() ?: stringResource(R.string.unknown_label), value.toString())
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Inbound Waveform
            RegularText(text = stringResource(R.string.call_quality_metrics_inbound_audio_level),
                size = Dimens.textSize16sp,
                fontWeight = FontWeight.SemiBold)

            Spacer(modifier = Modifier.height(4.dp))

            AudioWaveform(
                audioLevels = inboundLevels,
                barColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Outbound Waveform
            RegularText(text = stringResource(R.string.call_quality_metrics_outbound_audio_level),
                size = Dimens.textSize16sp,
                fontWeight = FontWeight.SemiBold)

            Spacer(modifier = Modifier.height(4.dp))

            AudioWaveform(
                audioLevels = outboundLevels,
                barColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            )
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
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.size4dp))
            .background(secondary_background_color)
            .padding(start = Dimens.mediumPadding, end = Dimens.mediumPadding, top = Dimens.smallPadding, bottom = Dimens.smallPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RegularText(
            modifier = Modifier
                .padding(end = Dimens.smallPadding),
            text = label)

        Spacer(modifier = Modifier.weight(1f))

        RegularText(text = value,
            size = Dimens.textSize16sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1)
    }
}
