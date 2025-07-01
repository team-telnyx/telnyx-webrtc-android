package org.telnyx.webrtc.compose_app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.common.model.Profile
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.MainGreen
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedLabeledEdiText


@Composable
fun CredentialTokenView(
    profile: Profile? = null,
    onSave: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var isTokenState by remember { mutableStateOf(profile?.sipToken?.isNotEmpty() == true) }
    val context = LocalContext.current

    var sipToken by remember { mutableStateOf(profile?.sipToken ?: "") }

    var sipUsername by remember { mutableStateOf(profile?.sipUsername ?: "") }
    var sipPassword by remember { mutableStateOf(profile?.sipPass ?: "") }
    var callerIdName by remember { mutableStateOf(profile?.callerIdName ?: "") }
    var callerIdNumber by remember { mutableStateOf(profile?.callerIdNumber ?: "") }
    var forceRelayCandidate by remember { mutableStateOf(profile?.forceRelayCandidate ?: false) }


    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("credentialsForm")
            .background(Color.White)
    ) {


        CredentialTokenSwitcher(isTokenState) {
            isTokenState = it
        }

        if (!isTokenState) {

            OutlinedLabeledEdiText(
                text = sipUsername,
                hint = stringResource(R.string.username_hint),
                label = stringResource(R.string.username),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().testTag("sipUsername")
            ) { value ->
                sipUsername = value
            }

            OutlinedLabeledEdiText(
                text = sipPassword,
                hint = stringResource(R.string.password_hint),
                label = stringResource(R.string.password),
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().testTag("sipPassword")
            ) { value ->
                sipPassword = value
            }
        } else {
            OutlinedLabeledEdiText(
                text = sipToken,
                hint = stringResource(R.string.token_hint),
                label = stringResource(R.string.token),
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            ) { value ->
                sipToken = value
            }
        }

        OutlinedLabeledEdiText(
            text = callerIdName,
            hint = stringResource(R.string.caller_name_hint),
            label = stringResource(R.string.caller_name),
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth().testTag("callerIDName")
        ) { value ->
            callerIdName = value
        }

        OutlinedLabeledEdiText(
            text = callerIdNumber,
            hint = stringResource(R.string.caller_number_hint),
            label = stringResource(R.string.caller_number),
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth().testTag("callerIDNumber")
        ) { value ->
            callerIdNumber = value
        }

        // Force Relay Candidate Switch
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Dimens.spacing8dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Force TURN Relay",
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = forceRelayCandidate,
                onCheckedChange = { forceRelayCandidate = it }
            )
        }

        Spacer(modifier = Modifier.height(Dimens.spacing8dp))

        Box(modifier = Modifier.fillMaxWidth()) {
            PosNegButton(
                positiveText = stringResource(id = R.string.save),
                negativeText = stringResource(id = R.string.Cancel),
                contentAlignment = Alignment.BottomStart,
                onPositiveClick = {
                    if (!isTokenState) {
                        if (sipUsername.isEmpty() || sipPassword.isEmpty() || callerIdName.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.empty_profile_fields_message), Toast.LENGTH_SHORT).show()
                            return@PosNegButton
                        }

                        onSave(
                            Profile(
                                sipUsername = sipUsername.trim(),
                                sipPass = sipPassword,
                                callerIdName = callerIdName.trim(),
                                callerIdNumber = callerIdNumber.trim(),
                                isUserLoggedIn = true,
                                forceRelayCandidate = forceRelayCandidate
                            ),
                        )
                    } else {
                        if (sipToken.isEmpty() || callerIdName.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.empty_profile_fields_message), Toast.LENGTH_SHORT).show()
                            return@PosNegButton
                        }

                        onSave(
                            Profile(
                                sipToken = sipToken.trim(),
                                callerIdName = callerIdName.trim(),
                                callerIdNumber = callerIdNumber.trim(),
                                isUserLoggedIn = true,
                                forceRelayCandidate = forceRelayCandidate
                            )
                        )
                    }
                },
                onNegativeClick = onDismiss
            )
        }
    }


}

@Composable
fun CredentialTokenSwitcher(isTokenState: Boolean, onCheckedChange: (Boolean) -> Unit) {

    Row(
        modifier = Modifier
            .padding(top = Dimens.spacing8dp)
            .fillMaxWidth()
            .wrapContentHeight()
            .clip(RoundedCornerShape(Dimens.size4dp))
            .border(Dimens.borderStroke1dp,
                Color.Black,
                RoundedCornerShape(Dimens.size4dp)),
        horizontalArrangement = Arrangement.Center
    ) {
        ToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(id = R.string.credential_login),
            isSelected = !isTokenState,
            onClick = { onCheckedChange(false) }
        )
        ToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(id = R.string.token_login),
            isSelected = isTokenState,
            onClick = { onCheckedChange(true) }
        )
    }
}

@Composable
fun ToggleButton(
    modifier: Modifier,
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = if (isSelected) {
        MainGreen
    } else {
        Color.White
    }

    val textColor = if (isSelected) {
        Color.White
    } else {
        Color.Black
    }

    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = backgroundColor,
            contentColor = textColor
        ),
        modifier = modifier
            .height(Dimens.size48dp),
        shape = RoundedCornerShape(0.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            focusedElevation = 0.dp,
            hoveredElevation = 0.dp
        )
    ) {
        Text(text,
            fontSize = Dimens.textSize16sp,
            fontWeight = FontWeight.Medium)
    }
}

@Preview
@Composable
fun CredentialTokenViewPreview() {
    TelnyxAndroidWebRTCSDKTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            innerPadding.calculateTopPadding()
            CredentialTokenView(null, {}) { }
        }
    }
}
