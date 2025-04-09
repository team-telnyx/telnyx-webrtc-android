package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


@Composable
fun MediumTextBold(
    text: String?,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    style: TextStyle = LocalTextStyle.current,
) {
    Text(
        text ?: "",
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

@Composable
fun MediumTextBold(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    textSize: TextUnit = 16.sp,
    color: Color = MaterialTheme.colorScheme.onSurface,
    textAlign: TextAlign = TextAlign.Start,
    fontWeight: FontWeight = FontWeight.Medium,
) {
    Text(
        text,
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        fontWeight = fontWeight,
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
    fontWeight: FontWeight = FontWeight.Medium,
) {
    Text(
        text ?: "",
        modifier = modifier,
        textAlign = textAlign,
        color = color,
        fontSize = textSize,
        fontWeight = fontWeight,
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

@Composable
fun OutlinedEdiText(
    modifier: Modifier = Modifier,
    hint: String,
    text: String,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onTextChanged: (String) -> Unit
) {
    OutlinedTextField(
        modifier = modifier,
        value = text,
        onValueChange = onTextChanged,
        enabled = enabled,
        isError = isError,
        visualTransformation = if (keyboardType == KeyboardType.Password) PasswordVisualTransformation() else VisualTransformation.None,
        label = {
            Text(hint, color = Color.Gray)
        },
        keyboardOptions = KeyboardOptions.Default.copy(keyboardType = keyboardType, imeAction = imeAction),
        shape = RoundedCornerShape(8.dp)
    )
}
