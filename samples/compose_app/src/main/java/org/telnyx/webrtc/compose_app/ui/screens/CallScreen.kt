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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.rememberNavController
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.CallQuality
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
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
    var showCallQualityMetrics by remember { mutableStateOf(false) }
    var destinationNumber by remember { mutableStateOf("") }
    val callQualityMetrics by telnyxViewModel.callQualityMetrics.collectAsState()
    val inboundLevels by telnyxViewModel.inboundAudioLevels.collectAsState()
    val outboundLevels by telnyxViewModel.outboundAudioLevels.collectAsState()

    LaunchedEffect(uiState) {
        callUIState = when (uiState) {
            is TelnyxSocketEvent.OnClientReady -> CallUIState.IDLE
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

        // Add the toggle button at the top
        AnimatedContent(targetState = callUIState, label = "Animated call area") { callState ->
            if (callState == CallUIState.IDLE) {
                DestinationTypeSwitcher(isPhoneNumber) {
                    isPhoneNumber = it
                }
            }
        }

        AnimatedContent(targetState = callUIState, label = "Animated call area") { callState ->
            if (callState != CallUIState.INCOMING) {
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
                        HomeIconButton(Modifier.testTag("call"), icon = R.drawable.baseline_call_24, backGroundColor = telnyxGreen, contentColor = Color.Black) {
                            if (destinationNumber.isNotEmpty())
                                telnyxViewModel.sendInvite(context, destinationNumber, true)
                        }
                    }
                    CallUIState.ACTIVE ->  {
                        Column(horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing),
                            modifier = Modifier.testTag("callActiveView")) {
                            
                            // Call quality summary (only shown when metrics are available)
                            if (callQualityMetrics != null) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = Dimens.spacing16dp)
                                ) {
                                    // Label: Call quality
                                    MediumTextBold(
                                        text = "Call quality",
                                        textSize = 16.sp,
                                        modifier = Modifier.padding(bottom = Dimens.spacing4dp)
                                    )
                                    
                                    // Quality indicator row with button
                                    Row(
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        // Quality indicator with colored dot
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            val (color, text) = when (callQualityMetrics?.quality) {
                                                CallQuality.EXCELLENT -> Color(0xFF4CAF50) to "Excellent"
                                                CallQuality.GOOD -> Color(0xFF8BC34A) to "Good"
                                                CallQuality.FAIR -> Color(0xFFFFC107) to "Fair"
                                                CallQuality.POOR -> Color(0xFFFF9800) to "Poor"
                                                CallQuality.BAD -> Color(0xFFF44336) to "Bad"
                                                else -> Color(0xFF9E9E9E) to "Unknown"
                                            }
                                            
                                            // Colored dot
                                            Box(
                                                modifier = Modifier
                                                    .size(12.dp)
                                                    .background(color, RoundedCornerShape(6.dp))
                                            )
                                            
                                            Spacer(modifier = Modifier.width(8.dp))
                                            
                                            // Quality text
                                            Text(
                                                text = text,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                        
                                        // "View all call metrics" button
                                        Surface(
                                            shape = RoundedCornerShape(16.dp),
                                            color = Color.Transparent,
                                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Button(
                                                onClick = { showCallQualityMetrics = true },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = Color.Transparent,
                                                    contentColor = MaterialTheme.colorScheme.primary
                                                ),
                                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Text("View all call metrics", fontSize = 14.sp)
                                            }
                                        }
                                    }
                                }
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
            metrics = callQualityMetrics,
            inboundLevels = inboundLevels,
            outboundLevels = outboundLevels
        ) {
            showCallQualityMetrics = false
        }
    }
}



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallQualityMetricsBottomSheet(
    metrics: com.telnyx.webrtc.sdk.stats.CallQualityMetrics,
    inboundLevels: List<Float>,
    outboundLevels: List<Float>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        onDismissRequest = {
            onDismiss.invoke()
        },
        containerColor = Color.White,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier.padding(Dimens.mediumSpacing)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                MediumTextBold(
                    text = "Call Quality Metrics",
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
    onClick: () -> Unit
) {

    IconButton(
        modifier = modifier.size(Dimens.size60dp),
        colors = IconButtonDefaults.iconButtonColors(containerColor = backGroundColor, contentColor = contentColor ?: Color.Black),
        onClick = onClick) {
        Image(painter = painterResource(icon),
            contentDescription = "",
            modifier = Modifier.padding(Dimens.smallSpacing),
            colorFilter = ColorFilter.tint(contentColor ?: Color.Black))
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
