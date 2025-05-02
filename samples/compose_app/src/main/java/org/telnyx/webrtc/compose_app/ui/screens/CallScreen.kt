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
import org.telnyx.webrtc.compose_app.ui.components.CallQualityDisplay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText
import timber.log.Timber

private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

@Composable
fun CallScreen(telnyxViewModel: TelnyxViewModel) {
    val context = LocalContext.current

    val uiState by telnyxViewModel.uiState.collectAsState()
    var callUIState by remember { mutableStateOf(CallUIState.IDLE) }
    val loudSpeakerOn = telnyxViewModel.currentCall?.getIsOnLoudSpeakerStatus()?.observeAsState(initial = false)
    val isMuted = telnyxViewModel.currentCall?.getIsMuteStatus()?.observeAsState(initial = false)
    val isHolded = telnyxViewModel.currentCall?.getIsOnHoldStatus()?.observeAsState(initial = false)

    var showDialpadSection by remember { mutableStateOf(false) }
    val callQualityMetrics by telnyxViewModel.callQualityMetrics.collectAsState()
    val inboundLevels by telnyxViewModel.inboundAudioLevels.collectAsState()
    val outboundLevels by telnyxViewModel.outboundAudioLevels.collectAsState()

    LaunchedEffect(uiState) {
        callUIState = when (uiState) {
            is TelnyxSocketEvent.OnClientReady -> CallUIState.IDLE
            is TelnyxSocketEvent.OnClientError -> {
                val errorMessage = (uiState as TelnyxSocketEvent.OnClientError).message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                CallUIState.IDLE
            }
            is TelnyxSocketEvent.OnIncomingCall -> {
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
            modifier = Modifier.fillMaxWidth().testTag("callInput"),
            imeAction = ImeAction.Done
        ) {
            destinationNumber = it
        }
        Box (
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {

            AnimatedContent(targetState = callUIState, label = "Animated call area")  { callState ->
                when (callState) {
                    CallUIState.IDLE -> {
                        HomeIconButton(Modifier.testTag("call"), icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                            if (destinationNumber.isNotEmpty())
                                telnyxViewModel.sendInvite(context, destinationNumber, true)
                        }
                    }
                    CallUIState.ACTIVE ->  {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                            modifier = Modifier.testTag("callActiveView")) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HomeIconButton(Modifier.testTag("mute"), icon = if (isMuted?.value == true) R.drawable.mute_off_24 else R.drawable.mute_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.currentCall?.onMuteUnmutePressed()
                                }

                                HomeIconButton(Modifier.testTag("loudSpeaker"), icon = if (loudSpeakerOn?.value == true) R.drawable.speaker_24 else R.drawable.speaker_off_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.currentCall?.onLoudSpeakerPressed()
                                }

                                HomeIconButton(Modifier.testTag("hold"), icon = if (isHolded?.value == true) R.drawable.pause_24 else R.drawable.play_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.holdUnholdCurrentCall(context)
                                }

                                HomeIconButton(Modifier.testTag("dialpad"), icon = R.drawable.dialpad_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    showDialpadSection = true
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HomeIconButton(Modifier.testTag("endCall"), icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                                    telnyxViewModel.endCall(context)
                                }
                            }
                            // Display call quality metrics when available
                            CallQualityDisplay(
                                metrics = callQualityMetrics,
                                inboundLevels = inboundLevels,
                                outboundLevels = outboundLevels
                            )
                        }

                    }
                    CallUIState.INCOMING ->  {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Dimens.extraLargeSpacing),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HomeIconButton(Modifier.testTag("callReject"), icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White) {
                                val inviteResponse = (uiState as TelnyxSocketEvent.OnIncomingCall).message
                                Timber.i("Reject call UI ${inviteResponse.callId}")
                                telnyxViewModel.rejectCall(context, inviteResponse.callId)
                            }
                            HomeIconButton(Modifier.testTag("callAnswer"), icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                                val inviteResponse = (uiState as TelnyxSocketEvent.OnIncomingCall).message
                                telnyxViewModel.answerCall(context, inviteResponse.callId, inviteResponse.callerIdNumber, true)
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
    modifier: Modifier,
    icon: Int,
    backGroundColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {

    IconButton(
        modifier = modifier.size(Dimens.size60dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = backGroundColor, contentColor = contentColor),
        onClick = onClick) {
        Image(painter = painterResource(icon),
            contentDescription = "",
            modifier = Modifier.padding(Dimens.smallSpacing),
            colorFilter = ColorFilter.tint(Color.Black))
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
