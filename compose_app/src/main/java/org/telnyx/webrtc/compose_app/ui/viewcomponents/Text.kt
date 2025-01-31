package org.telnyx.webrtc.compose_app.ui.viewcomponents


import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp


@Composable
fun MediumTextBold(
    text: String?,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight = FontWeight.Medium,
    fontStyle: FontStyle? = null,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    textDecoration: TextDecoration? = null
) {
    Text(
        text ?: "",
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        fontStyle = fontStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
        textDecoration = textDecoration
    )
}

@Composable
fun MediumTextBold(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight = FontWeight.Medium,
    fontStyle: FontStyle? = null,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text,
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        fontStyle = fontStyle,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun TextHeader(
    text: String?,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 18.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    lineHeight: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight = FontWeight.Medium,
    fontStyle: FontStyle? = null
) {
    Text(
        text ?: "",
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        fontStyle = fontStyle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun RegularText(
    text: String?,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: TextUnit = 14.sp,
    textAlign: TextAlign = TextAlign.Start,
    fontStyle: FontStyle = FontStyle.Normal
) {
    Text(
        text ?: "",
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Normal,
        textAlign = textAlign,
        fontStyle = fontStyle,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
fun RegularText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    size: TextUnit = 14.sp,
    textAlign: TextAlign = TextAlign.Start
) {
    Text(
        text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = FontWeight.Normal,
        textAlign = textAlign,
        softWrap = false,
        maxLines = 1
    )
}
