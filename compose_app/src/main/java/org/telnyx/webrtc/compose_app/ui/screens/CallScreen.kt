package org.telnyx.webrtc.compose_app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.TelnyxViewModel
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText

@Composable
fun CallScreen(telnyxViewModel: TelnyxViewModel) {


    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
        modifier = Modifier.verticalScroll(rememberScrollState())
    ) {
        var destinationNumber by remember { mutableStateOf("") }
        OutlinedEdiText(
            text = destinationNumber,
            hint = stringResource(R.string.destination),
            modifier = Modifier.fillMaxWidth(),
        ) {
            destinationNumber = it
        }
        Box (
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {

            AnimatedContent(targetState = destinationNumber.isEmpty())  { isNotEmpty ->
                when (isNotEmpty) {
                    true -> {
                        HomeIconButton(icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                        }
                    }
                    else ->  {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeIconButton(icon = R.drawable.mute_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                            }
                            HomeIconButton(icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                            }
                            HomeIconButton(icon = R.drawable.speaker_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                            }
                        }

                    }
                }
            }


        }
    }
}



@Composable
fun HomeIconButton(
    icon: Int,
    backGroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {

    IconButton(
        modifier = Modifier.size(Dimens.size60dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = backGroundColor, contentColor = contentColor),
        onClick = onClick) {
        Image(painter = painterResource(icon), contentDescription = "",modifier = Modifier.padding(Dimens.smallSpacing))
    }
}