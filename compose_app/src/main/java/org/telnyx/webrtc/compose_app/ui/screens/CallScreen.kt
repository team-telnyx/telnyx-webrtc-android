package org.telnyx.webrtc.compose_app.ui.screens

import android.widget.Toast
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText

@Composable
fun CallScreen(telnyxViewModel: TelnyxViewModel) {
    val context = LocalContext.current
    val uiState by telnyxViewModel.uiState.collectAsState()
    var callUIState by remember { mutableStateOf<CallUIState>(CallUIState.IDLE) }
    val loudSpeakerOn = telnyxViewModel.currentCall?.getIsOnLoudSpeakerStatus()?.observeAsState(initial = false)
    val isMuted = telnyxViewModel.currentCall?.getIsMuteStatus()?.observeAsState(initial = false)

    LaunchedEffect(uiState) {
        callUIState = when (uiState) {
            is TelnyxSocketEvent.OnClientReady -> CallUIState.IDLE
            is TelnyxSocketEvent.OnClientError -> {
                val errorMessage = (uiState as TelnyxSocketEvent.OnClientError).message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                CallUIState.IDLE
            }
            is TelnyxSocketEvent.OnIncomingCall -> {
                //onCallIncoming(uiState.message.callId, uiState.message.callerIdNumber)
                CallUIState.INCOMING
            }
            is TelnyxSocketEvent.OnCallAnswered -> {
                CallUIState.ACTIVE
            }
            is TelnyxSocketEvent.OnCallEnded -> {
                if (telnyxViewModel.currentCall != null)
                    CallUIState.ACTIVE
                else
                    CallUIState.IDLE
            }
            is TelnyxSocketEvent.OnRinging -> {
                CallUIState.ACTIVE
            }
            else -> {
                CallUIState.IDLE
            }
        }
    }

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

            AnimatedContent(targetState = callUIState)  { callState ->
                when (callState) {
                    CallUIState.IDLE -> {
                        HomeIconButton(icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                            if (destinationNumber.isNotEmpty())
                                telnyxViewModel.sendInvite(context, destinationNumber)
                        }
                    }
                    CallUIState.ACTIVE ->  {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeIconButton(icon = if (isMuted?.value == true) R.drawable.mute_24 else R.drawable.mute_off_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                telnyxViewModel.currentCall?.onMuteUnmutePressed()
                            }
                            HomeIconButton(icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                                telnyxViewModel.endCall(context)
                            }
                            HomeIconButton(icon = if (loudSpeakerOn?.value == true) R.drawable.speaker_off_24 else R.drawable.speaker_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                telnyxViewModel.currentCall?.onLoudSpeakerPressed()
                            }
                        }

                    }
                    CallUIState.INCOMING ->  {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.extraLargeSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeIconButton(icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                                telnyxViewModel.endCall(context)
                            }
                            HomeIconButton(icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                                val inviteResponse = (uiState as TelnyxSocketEvent.OnIncomingCall).message
                                telnyxViewModel.answerCall(context, inviteResponse.callId, inviteResponse.callerIdNumber)
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

private enum class CallUIState {
    IDLE,
    INCOMING,
    ACTIVE
}
