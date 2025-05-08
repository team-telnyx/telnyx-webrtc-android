package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme

@Composable
fun RoundSmallButton(
    modifier: Modifier = Modifier,
    text: String,
    textSize: TextUnit = 16.sp,
    backgroundColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderStroke: Dp = 1.dp,
    borderColor: Color = contentColor,
    isLoading:Boolean = false,
    icon: Painter? = null,
    iconContentDescription: String? = null,
    onClick: () -> Unit
) {
    Box (
        modifier = modifier
            .background(backgroundColor, shape = Dimens.shape20dp)
            .clip(shape = shape100Percent)
            .border(borderStroke, borderColor, shape = Dimens.shape20dp)
            .clickable {
                onClick()
            }
            .padding(horizontal = Dimens.largePadding),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading){
            GenericCircleProgressIndicator()
        }else{
            Row (verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(
                        painter = it,
                        contentDescription = iconContentDescription,
                        modifier = Modifier.size(Dimens.size16dp)
                    )

                    Spacer(modifier = Modifier.width(Dimens.spacing4dp))
                }

                MediumTextBold(text = text, color = contentColor,textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun RoundedOutlinedButton(
    modifier: Modifier = Modifier,
    text: String,
    textSize: TextUnit = 16.sp,
    backgroundColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.background,
    borderStroke: Dp = 1.dp,
    borderColor: Color = contentColor,
    isLoading:Boolean = false,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor, contentColor),
        shape = Dimens.shape20dp,
        modifier = modifier,
        border = BorderStroke(borderStroke,borderColor),
        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
        contentPadding = PaddingValues(vertical = 0.dp, horizontal = Dimens.spacing12dp)
    ) {
        if (isLoading){
            GenericCircleProgressIndicator()
        }else{
            MediumTextBold(text = text, color = contentColor,textSize = textSize)
        }
    }
}

@Preview
@Composable
fun Test() {
    TelnyxAndroidWebRTCSDKTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            innerPadding.calculateTopPadding()
            RoundSmallButton(
                modifier = Modifier.height(30.dp),
                text = stringResource(id = R.string.add_new_profile),
                textSize = 12.sp,
                backgroundColor = MaterialTheme.colorScheme.secondary,
                icon = painterResource(R.drawable.ic_add)
            ) { }
        }
    }

}