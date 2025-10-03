package org.telnyx.webrtc.compose_app.ui.viewcomponents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
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
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.colorPrimaryVariant


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
    fontStyle: FontStyle = FontStyle.Normal,
    fontWeight: FontWeight = FontWeight.Normal,
    maxLines: Int = 2
) {
    Text(
        text ?: "",
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = fontWeight,
        textAlign = textAlign,
        fontStyle = fontStyle,
        overflow = TextOverflow.Ellipsis,
        maxLines = maxLines
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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutlinedLabeledEdiText(
    modifier: Modifier = Modifier,
    hint: String,
    text: String,
    label: String,
    isError: Boolean = false,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onTextChanged: (String) -> Unit
) {
    var passwordVisible by remember { mutableStateOf(false) }

    Column (modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
        horizontalAlignment = Alignment.Start) {

        RegularText(label,
            color = colorPrimaryVariant)

        Spacer(modifier = Modifier.height(Dimens.spacing4dp))

        OutlinedTextField(
            modifier = modifier,
            value = text,
            onValueChange = onTextChanged,
            enabled = enabled,
            isError = isError,
            visualTransformation = if (keyboardType == KeyboardType.Password && !passwordVisible) PasswordVisualTransformation() else VisualTransformation.None,
            label = {
                Text(hint, color = Color.Gray)
            },
            keyboardOptions = KeyboardOptions.Default.copy(keyboardType = keyboardType, imeAction = imeAction),
            shape = RoundedCornerShape(8.dp),
            trailingIcon = {
                if (keyboardType == KeyboardType.Password) {
                    val image = if (passwordVisible)
                        ImageVector.vectorResource(R.drawable.hide)
                    else
                        ImageVector.vectorResource(R.drawable.view)

                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(imageVector = image, contentDescription = if (passwordVisible) "Ukryj hasło" else "Pokaż hasło")
                    }
                }
            }
        )
    }
}
