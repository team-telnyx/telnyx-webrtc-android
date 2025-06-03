package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.secondary_background_color
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText

/**
 * A row displaying a metric label and value.
 *
 * @param label The label for the metric.
 * @param value The value of the metric.
 * @param modifier Optional modifier for the component.
 */
@Composable
fun MetricRow(
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