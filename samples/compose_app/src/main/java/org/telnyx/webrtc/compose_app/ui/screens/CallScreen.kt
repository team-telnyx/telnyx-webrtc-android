package org.telnyx.webrtc.compose_app.ui.screens

import android.media.AudioManager
import android.media.ToneGenerator
import android.media.ToneGenerator.*
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.CallQualityBadColor
import org.telnyx.webrtc.compose_app.ui.theme.CallQualityFairColor
import org.telnyx.webrtc.compose_app.ui.theme.CallQualityGoodColor
import org.telnyx.webrtc.compose_app.ui.theme.CallQualityPoorColor
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent
import org.telnyx.webrtc.compose_app.ui.theme.DroppedIconColor
import org.telnyx.webrtc.compose_app.ui.theme.MainGreen
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.OutlinedEdiText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.ui.screens.assistant.AssistantTranscriptBottomSheet
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundSmallButton
import org.telnyx.webrtc.compose_app.utils.Utils
import org.telnyx.webrtc.compose_app.utils.capitalizeFirstChar
import timber.log.Timber
import org.telnyx.webrtc.compose_app.ui.screens.CallHistoryBottomSheet

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
    var showCallQualityMetrics by remember { mutableStateOf(false) }
    var showCallHistoryBottomSheet by remember { mutableStateOf(false) }
    var showAssistantTranscriptBottomSheet by remember { mutableStateOf(false) }
    var destinationNumber by remember { mutableStateOf("") }
    val callQualityMetrics by telnyxViewModel.callQualityMetrics.collectAsState()
    val inboundLevels by telnyxViewModel.inboundAudioLevels.collectAsState()
    val outboundLevels by telnyxViewModel.outboundAudioLevels.collectAsState()

    LaunchedEffect(uiState) {
        callUIState = when (uiState) {
            is TelnyxSocketEvent.OnClientReady -> {
                if (telnyxViewModel.currentCall != null) {
                    destinationNumber = telnyxViewModel.currentCallCallerName ?: ""
                    CallUIState.ACTIVE
                } else
                    CallUIState.IDLE
            }
            is TelnyxSocketEvent.OnIncomingCall -> {
                CallUIState.INCOMING
            }
            is TelnyxSocketEvent.OnCallAnswered,
            is TelnyxSocketEvent.OnCallDropped,
            is TelnyxSocketEvent.OnCallReconnecting -> {
                CallUIState.ACTIVE
            }
            is TelnyxSocketEvent.OnCallEnded -> {
                destinationNumber = ""
                showAssistantTranscriptBottomSheet = false

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
        verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)
    ) {
        var isPhoneNumber by remember { mutableStateOf(false) }

        // Add the toggle button at the top - only show when not using Assistant Login
        AnimatedContent(targetState = callUIState, label = "Animated call area") { callState ->
            if (callState == CallUIState.IDLE && !telnyxViewModel.isAnonymouslyConnected) {
                DestinationTypeSwitcher(isPhoneNumber) {
                    isPhoneNumber = it
                }
            }
        }

        AnimatedContent(targetState = callUIState, label = "Animated call area") { callState ->
            if (callState != CallUIState.INCOMING && !telnyxViewModel.isAnonymouslyConnected) {
                OutlinedEdiText(
                    text = destinationNumber,
                    hint = if (isPhoneNumber) stringResource(R.string.phone_number_hint) else stringResource(R.string.sip_address_hint),
                    modifier = Modifier.fillMaxWidth().testTag("callInput"),
                    imeAction = ImeAction.Done,
                    keyboardType = if (isPhoneNumber) androidx.compose.ui.text.input.KeyboardType.Phone else androidx.compose.ui.text.input.KeyboardType.Text,
                    enabled = callUIState != CallUIState.ACTIVE
                ) {
                    destinationNumber = it
                }
            }
        }

        Box (
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Dimens.spacing24dp),
            contentAlignment = Alignment.Center
        ) {

            AnimatedContent(targetState = callUIState, label = "Animated call area")  { callState ->
                when (callState) {
                    CallUIState.IDLE -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.spacing18dp)
                        ) {
                            if (telnyxViewModel.isAnonymouslyConnected) {
                                // Assistant mode - only show the Assistant button
                                HomeButton(
                                    modifier = Modifier.testTag("assistantCall"),
                                    text = stringResource(R.string.assistant_dial),
                                    icon = R.drawable.baseline_call_24,
                                    backGroundColor = telnyxGreen,
                                    contentColor = Color.White
                                ) {
                                    telnyxViewModel.sendAiAssistantInvite(context, true)
                                }
                            } else {
                                // Regular mode - show green call button
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacing16dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    HomeIconButton(Modifier.testTag("call"), icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                                        if (destinationNumber.isNotEmpty())
                                            telnyxViewModel.sendInvite(context, destinationNumber, true)
                                    }
                                }
                            }
                            
                            RoundSmallButton(
                                text = stringResource(R.string.call_history_title),
                                textSize = 14.sp,
                                backgroundColor = MaterialTheme.colorScheme.background
                            ) {
                                showCallHistoryBottomSheet = true
                            }
                        }
                    }
                    CallUIState.ACTIVE ->  {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                            modifier = Modifier.testTag("callActiveView")) {
                            
                            // Call quality summary (only shown when metrics are available)
                            callQualityMetrics?.let {
                                CallMetricsState(it) { showCallQualityMetrics = true }
                            }
                            
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

                                HomeIconButton(Modifier.testTag("hold"), icon = if (isHolded?.value == true) R.drawable.play_24 else R.drawable.pause_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                    telnyxViewModel.holdUnholdCurrentCall(context)
                                }

                                if (telnyxViewModel.isAnonymouslyConnected) {
                                    // Assistant mode - show message button to open transcript
                                    HomeIconButton(Modifier.testTag("assistantTranscript"), icon = R.drawable.ic_message, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                        showAssistantTranscriptBottomSheet = true
                                    }
                                } else {
                                    // Regular mode - show dialpad button
                                    HomeIconButton(Modifier.testTag("dialpad"), icon = R.drawable.dialpad_24, backGroundColor = MaterialTheme.colorScheme.secondary, contentColor = Color.Black) {
                                        showDialpadSection = true
                                    }
                                }
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                HomeIconButton(Modifier.testTag("endCall"), icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.White, stringResource(R.string.end)) {
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
                            HomeIconButton(Modifier.testTag("callReject"), icon = R.drawable.baseline_call_end_24, backGroundColor = callRed, contentColor = Color.Black) {
                                val inviteResponse = (uiState as TelnyxSocketEvent.OnIncomingCall).message
                                Timber.i("Reject call UI ${inviteResponse.callId}")
                                telnyxViewModel.rejectCall(context, inviteResponse.callId)
                            }
                            HomeIconButton(Modifier.testTag("callAnswer"), icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                                val inviteResponse = (uiState as TelnyxSocketEvent.OnIncomingCall).message
                                destinationNumber = inviteResponse.callerIdName
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
    
    if (showCallQualityMetrics && callQualityMetrics != null) {
        CallQualityMetricsBottomSheet(
            metrics = callQualityMetrics!!,
            inboundLevels = inboundLevels,
            outboundLevels = outboundLevels
        ) {
            showCallQualityMetrics = false
        }
    }

    if (showCallHistoryBottomSheet) {
        CallHistoryBottomSheet(
            telnyxViewModel = telnyxViewModel
        ) { number ->
            number?.let {
                destinationNumber = it
            }
            showCallHistoryBottomSheet = false
        }
    }

    if (showAssistantTranscriptBottomSheet) {
        AssistantTranscriptBottomSheet(
            telnyxViewModel = telnyxViewModel
        ) {
            showAssistantTranscriptBottomSheet = false
        }
    }
}

@Composable
fun CallMetricsState(metrics: CallQualityMetrics, onClick: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(bottom = Dimens.spacing24dp),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacing4dp),
        horizontalAlignment = Alignment.Start
    ) {
        // Label: Call quality
        RegularText(text = stringResource(id = R.string.call_metrics_label))

        // Quality indicator row with button
        Box (contentAlignment = Alignment.Center) {
            // Quality indicator with colored dot
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = when (metrics.quality) {
                    CallQuality.EXCELLENT -> MainGreen
                    CallQuality.GOOD -> CallQualityGoodColor
                    CallQuality.FAIR -> CallQualityFairColor
                    CallQuality.POOR -> CallQualityPoorColor
                    CallQuality.BAD -> CallQualityBadColor
                    else -> DroppedIconColor
                }

                val text = metrics.quality.name.capitalizeFirstChar()

                // Colored dot
                Box(
                    modifier = Modifier
                        .size(Dimens.size12dp)
                        .background(
                            color = color,
                            shape = shape100Percent
                        )
                )
                RegularText(
                    text = text
                )
            }

            RoundSmallButton(
                text = stringResource(R.string.call_metrics_button),
                textSize = 14.sp,
                backgroundColor = MaterialTheme.colorScheme.background
            ) {
                onClick.invoke()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallQualityMetricsBottomSheet(
    metrics: CallQualityMetrics,
    inboundLevels: List<Float>,
    outboundLevels: List<Float>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        modifier = Modifier.fillMaxSize(),
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
                    text = stringResource(id = R.string.call_quality_metrics_title),
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
                        contentDescription = stringResource(id = R.string.close_button_dessc),
                        modifier = Modifier.size(Dimens.size16dp)
                    )
                }
            }

            // Display detailed call quality metrics
            CallQualityDisplay(
                metrics = metrics,
                inboundLevels = inboundLevels,
                outboundLevels = outboundLevels
            )
        }
    }
}

@Composable
fun HomeIconButton(
    modifier: Modifier,
    icon: Int,
    backGroundColor: Color,
    contentColor: Color?,
    contentDescription: String = "",
    onClick: () -> Unit
) {

    IconButton(
        modifier = modifier.size(Dimens.size60dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = backGroundColor, contentColor = contentColor ?: Color.Black),
        onClick = onClick) {
        Image(painter = painterResource(icon),
            contentDescription = contentDescription,
            modifier = Modifier.padding(Dimens.smallSpacing),
            colorFilter = ColorFilter.tint(contentColor ?: Color.Black))
    }
}

@Composable
fun HomeButton(
    modifier: Modifier = Modifier,
    text: String,
    icon: Int = R.drawable.baseline_call_24,
    backGroundColor: Color,
    contentColor: Color = Color.Black,
    contentDescription: String = "",
    onClick: () -> Unit
) {
    Button(
        modifier = modifier
            .fillMaxWidth(0.7f)
            .height(Dimens.size60dp),
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = backGroundColor,
            contentColor = contentColor
        ),
        shape = RoundedCornerShape(Dimens.buttonRoundedCorner),
        contentPadding = PaddingValues(horizontal = Dimens.smallSpacing, vertical = Dimens.extraSmallSpacing)
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                modifier = Modifier.size(Dimens.size24dp),
                colorFilter = ColorFilter.tint(contentColor)
            )
            Spacer(modifier = Modifier.width(Dimens.extraSmallSpacing))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
        }
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

@Composable
fun DestinationTypeSwitcher(isPhoneNumber: Boolean, onCheckedChange: (Boolean) -> Unit) {
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
            text = stringResource(id = R.string.sip_address_toggle),
            isSelected = !isPhoneNumber,
            onClick = { onCheckedChange(false) }
        )
        ToggleButton(
            modifier = Modifier.weight(1f),
            text = stringResource(id = R.string.phone_number_toggle),
            isSelected = isPhoneNumber,
            onClick = { onCheckedChange(true) }
        )
    }
}

private enum class CallUIState {
    IDLE,
    INCOMING,
    ACTIVE
}

@Preview
@Composable
fun CallScreenPreview() {
    val fakeViewModel = TelnyxViewModel()

    TelnyxAndroidWebRTCSDKTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            innerPadding.calculateTopPadding()
            CallScreen(telnyxViewModel = fakeViewModel)
        }
    }
}
