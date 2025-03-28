package org.telnyx.webrtc.compose_app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.telnyx.webrtc.common.model.Profile
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText


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


    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
        modifier = Modifier.verticalScroll(rememberScrollState()).testTag("credentialsForm")
    ) {


        CredentialTokenSwitcher(isTokenState) {
            isTokenState = it
        }

        if (!isTokenState) {
            OutlinedEdiText(
                text = sipUsername,
                hint = "SIP Username",
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().testTag("sipUsername")
            ) { value ->
                sipUsername = value
            }
            OutlinedEdiText(
                text = sipPassword,
                hint = "SIP Password",
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth().testTag("sipPassword")
            ) { value ->
                sipPassword = value
            }
        } else {
            OutlinedEdiText(
                text = sipToken,
                hint = "Token",
                imeAction = ImeAction.Next,
                modifier = Modifier.fillMaxWidth()
            ) { value ->
                sipToken = value
            }
        }

        OutlinedEdiText(
            text = callerIdName,
            hint = "Caller ID Name",
            imeAction = ImeAction.Next,
            modifier = Modifier.fillMaxWidth().testTag("callerIDName")
        ) { value ->
            callerIdName = value
        }

        OutlinedEdiText(
            text = callerIdNumber,
            hint = "Caller ID Number",
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Done,
            modifier = Modifier.fillMaxWidth().testTag("callerIDNumber")
        ) { value ->
            callerIdNumber = value
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            PosNegButton(
                positiveText = stringResource(id = R.string.save),
                negativeText = stringResource(id = R.string.Cancel),
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
                                isUserLoggedIn = true
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
                                isUserLoggedIn = true
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
        horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(checked = isTokenState, onCheckedChange = onCheckedChange)
        RegularText(
            text = if (!isTokenState) stringResource(id = R.string.credential_login) else stringResource(
                id = R.string.token_login
            )
        )
    }
}
