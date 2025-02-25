package org.telnyx.webrtc.compose_app.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import android.media.ToneGenerator.*
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
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText

private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

@Composable
fun CallScreen(telnyxViewModel: TelnyxViewModel) {
    val context = LocalContext.current

    val uiState by telnyxViewModel.uiState.collectAsState()
    var callUIState by remember { mutableStateOf<CallUIState>(CallUIState.IDLE) }
    val loudSpeakerOn = telnyxViewModel.currentCall?.getIsOnLoudSpeakerStatus()?.observeAsState(initial = false)
    val isMuted = telnyxViewModel.currentCall?.getIsMuteStatus()?.observeAsState(initial = false)
    val isHolded = telnyxViewModel.currentCall?.getIsOnHoldStatus()?.observeAsState(initial = false)

    var showDialpadSection by remember { mutableStateOf(false) }

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
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HomeIconButton(icon = if (isMuted?.value == true) R.drawable.mute_24 else R.drawable.mute_off_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.currentCall?.onMuteUnmutePressed()
                                }

                                HomeIconButton(icon = if (loudSpeakerOn?.value == true) R.drawable.speaker_off_24 else R.drawable.speaker_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.currentCall?.onLoudSpeakerPressed()
                                }

                                HomeIconButton(icon = if (isHolded?.value == true) R.drawable.play_24 else R.drawable.pause_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.holdUnholdCurrentCall(context)
                                }

                                HomeIconButton(icon = R.drawable.dialpad_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    showDialpadSection = true
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HomeIconButton(icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                                    telnyxViewModel.endCall(context)
                                }
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

    if (showDialpadSection) {
        DialpadSection(telnyxViewModel::dtmfPressed) {
            showDialpadSection = false
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadSection(onKeyPress: (String) -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()

    var selectedNumbers by remember { mutableStateOf("") }

    ModalBottomSheet(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        onDismissRequest = {
            onDismiss.invoke()
        },
        containerColor = Color.White,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(Dimens.mediumSpacing),
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MediumTextBold(
                    text = stringResource(id = R.string.dtmf_dialpad),
                    modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                )
                IconButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            onDismiss.invoke()
                        }
                    }
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(id = R.string.close_button_dessc)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {

                OutlinedEdiText(
                    text = selectedNumbers,
                    hint = stringResource(R.string.dtmf_dialpad),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    selectedNumbers = it
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                NumericKeyboard() { key ->
                    selectedNumbers += key
                    onKeyPress(key)
                }
            }
        }
    }
}

@Composable
fun NumericKeyboard(onKeyPress: (String) -> Unit) {
    val buttons = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#")
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()) {
        buttons.forEach { row ->
            Row {
                row.forEach { key ->
                    Button(
                        onClick = {
                            onKeyPress(key)
                            key.toIntOrNull()?.let {
                                onNumberClicked(it)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        ),
                        modifier = Modifier
                            .padding(Dimens.smallPadding)
                            .size(Dimens.size80dp)
                    ) {
                        MediumTextBold(text = key)
                    }
                }
            }
        }
    }
}

private fun onNumberClicked(number: Int) {
    when (number) {
        0 -> {
            toneGenerator.startTone(TONE_DTMF_0, 500)
        }
        1 -> {
            toneGenerator.startTone(TONE_DTMF_1, 500)
        }
        2 -> {
            toneGenerator.startTone(TONE_DTMF_2, 500)
        }
        3 -> {
            toneGenerator.startTone(TONE_DTMF_3, 500)
        }
        4 -> {
            toneGenerator.startTone(TONE_DTMF_4, 500)
        }
        5 -> {
            toneGenerator.startTone(TONE_DTMF_5, 500)
        }
        6 -> {
            toneGenerator.startTone(TONE_DTMF_6, 500)
        }
        7 -> {
            toneGenerator.startTone(TONE_DTMF_7, 500)
        }
        8 -> {
            toneGenerator.startTone(TONE_DTMF_8, 500)
        }
        9 -> {
            toneGenerator.startTone(TONE_DTMF_9, 500)
        }
    }

}

private enum class CallUIState {
    IDLE,
    INCOMING,
    ACTIVE
}