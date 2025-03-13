package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.tooling.preview.Preview
import org.telnyx.webrtc.compose_app.ui.theme.Dimens

@Composable
@Preview
fun GenericCircleProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.background){
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.testTag("PROGRESS_TEST_TAG")
            .fillMaxWidth()
            .padding(Dimens.mediumPadding)
            .background(Color.Transparent, shape = RoundedCornerShape(0))
    ) {
        CircularProgressIndicator(modifier = Modifier.size(Dimens.size24dp), color = color, strokeWidth = Dimens.size2dp)
    }
}