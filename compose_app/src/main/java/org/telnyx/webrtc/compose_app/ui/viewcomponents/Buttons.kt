package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent

@Composable
fun RoundedTextButton(
    modifier: Modifier = Modifier,
    text: String,
    backgroundColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.primary,
    borderStroke: Dp = 1.dp,
    borderColor: Color = contentColor,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(backgroundColor, contentColor),
        shape = Dimens.shape100Percent,
        modifier = modifier, border = BorderStroke(borderStroke,borderColor),
        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        MediumTextBold(text = text, modifier = Modifier.padding(Dimens.smallPadding), color = contentColor)
    }
}

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
    onClick: () -> Unit
) {
    Box (
        modifier = modifier.background(backgroundColor, shape = Dimens.shape100Percent).clip(shape = shape100Percent).clickable {
            onClick()
        }.padding( horizontal = Dimens.mediumPadding), contentAlignment = Alignment.Center
    ) {
        if (isLoading){
            GenericCircleProgressIndicator()
        }else{
            MediumTextBold(text = text, color = contentColor,textSize = textSize)
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
        shape = Dimens.shape100Percent,
        modifier = modifier, border = BorderStroke(borderStroke,borderColor),
        elevation = ButtonDefaults.elevatedButtonElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)
    ) {
        if (isLoading){
            GenericCircleProgressIndicator()
        }else{
            MediumTextBold(text = text, modifier = Modifier.padding(Dimens.smallPadding), color = contentColor,textSize = textSize)
        }
    }
}