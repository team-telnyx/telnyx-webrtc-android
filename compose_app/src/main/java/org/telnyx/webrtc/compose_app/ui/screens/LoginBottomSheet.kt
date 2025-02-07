package org.telnyx.webrtc.compose_app.ui.screens

import android.media.RingtoneManager
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.LogLevel
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText


@Composable
fun CredentialTokenView(
    onSave: (Profile) -> Unit,
    onDismiss: () -> Unit
) {
    var isTokenState by remember { mutableStateOf(false) }

    var sipToken by remember { mutableStateOf("") }

    var sipUsername by remember { mutableStateOf("") }
    var sipPassword by remember { mutableStateOf("") }
    var callerIdName by remember { mutableStateOf("") }
    var callerIdNumber by remember { mutableStateOf("") }


    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {


        CredSwitcher(isTokenState) {
            isTokenState = it
        }

        if (!isTokenState) {
            OutlinedEdiText(
                text = sipUsername,
                hint = "Sip Username",
                modifier = Modifier.fillMaxWidth()
            ) { value ->
                sipUsername = value
            }
            OutlinedEdiText(
                text = sipPassword,
                hint = "Sip Password",
                keyboardType = KeyboardType.Password,
                modifier = Modifier.fillMaxWidth()
            ) { value ->
                sipPassword = value
            }
        } else {
            OutlinedEdiText(
                text = sipToken,
                hint = "Token",
                modifier = Modifier.fillMaxWidth()
            ) { value ->
                sipToken = value
            }
        }

        OutlinedEdiText(
            text = callerIdName,
            hint = "Caller ID Name",
            modifier = Modifier.fillMaxWidth()
        ) { value ->
            callerIdName = value
        }

        OutlinedEdiText(
            text = callerIdNumber,
            hint = "Caller ID Number",
            modifier = Modifier.fillMaxWidth()
        ) { value ->
            callerIdNumber = value
        }

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            PosNegButton(
                positiveText = stringResource(id = R.string.save),
                negativeText = stringResource(id = R.string.Cancel),
                onPositiveClick = {
                    if (!isTokenState) {
                        onSave(
                            Profile(
                                sipUsername = sipUsername,
                                sipPass = sipPassword,
                                callerIdName = callerIdName,
                                callerIdNumber = callerIdNumber,
                                isUserLogin = true
                            ),
                        )
                    } else {
                        onSave(
                            Profile(
                                sipToken = sipToken,
                                callerIdName = callerIdName,
                                callerIdNumber = callerIdNumber,
                                isUserLogin = true
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
fun CredSwitcher(isTokenState: Boolean, onCheckedChange: (Boolean) -> Unit) {

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
