package com.telnyx.webrtc.sdk.utility.telecom.call

import android.annotation.SuppressLint
import android.os.SystemClock
import android.telecom.DisconnectCause
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PhonePaused
import androidx.compose.material.icons.rounded.SpeakerPhone
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCall
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallAction
import com.telnyx.webrtc.sdk.utility.telecom.model.TelecomCallRepository
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

@Composable
fun UnifiedCallUI(
    repository: TelecomCallRepository,
    telnyxCallManager: TelecomCallManager,
    acceptCall: Boolean?,
    onCallFinished: () -> Unit
) {
    LaunchedEffect(acceptCall) {
        if (acceptCall == true) {
            val call = repository.getCurrentRegisteredCall()
            call?.processAction(TelecomCallAction.Answer)
        }
    }
    // Observe the current Telecom call
    when (val call = repository.currentCall.collectAsState().value) {
        is TelecomCall.None, is TelecomCall.Unregistered -> {
            // Show a "call ended" or blank UI, then call onCallFinished
            LaunchedEffect(Unit) {
                onCallFinished()
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Call Ended")
            }
        }

        is TelecomCall.Registered -> {
            // Extract the fields we need
            val phoneNumber = call.callAttributes.address.schemeSpecificPart ?: "Unknown Number"
            val displayName = call.callAttributes.displayName
            val isIncoming = call.isIncoming()

            // Now call our new TelecomCallScreen that uses the TelnyxCallManager
            TelecomCallScreen(
                manager = telnyxCallManager,
                repository = repository,
                displayName = displayName.toString(),
                phoneNumber = phoneNumber,
                isIncoming = isIncoming,
                isActive = acceptCall ?: call.isActive,
                onCallFinished = onCallFinished
            )
        }
    }
}

/**
 * Main UI for a call screen. Displays call info, call timer, and call actions.
 */
@Composable
fun TelecomCallScreen(
    manager: TelecomCallManager,
    repository: TelecomCallRepository,
    displayName: String,
    phoneNumber: String,
    isIncoming: Boolean,
    isActive: Boolean,
    onCallFinished: () -> Unit
) {

    val telnyxCallState by produceState(CallState.NEW) {
        manager.callState.collect {
            value = it
            if (it == CallState.DONE) {
                val call = repository.getCurrentRegisteredCall()
                call?.processAction(TelecomCallAction.Disconnect(DisconnectCause(DisconnectCause.REMOTE)))
                onCallFinished()
            }
        }
    }

    var isMuted by remember { mutableStateOf(false) }
    var isOnHold by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(false) }

    val isCallActive = remember { mutableStateOf(isActive) }

    // A simple timer for "call duration," starts once the user "answers" or if isIncoming = false
    var elapsedMs by remember { mutableLongStateOf(0L) }
    val startTime = remember { SystemClock.elapsedRealtime() }

    LaunchedEffect(isCallActive.value) {
        while (isCallActive.value) {
            delay(1.seconds)
            elapsedMs = SystemClock.elapsedRealtime() - startTime
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Top half: call info + timer
            CallInfoSection(
                displayName = displayName,
                phoneNumber = phoneNumber,
                isActive = isCallActive.value,
                elapsedMs = elapsedMs,
                telnyxCallState = telnyxCallState
            )

            // If incoming + not active => show incoming actions
            if (isIncoming && !isCallActive.value) {
                IncomingCallActions(
                    onAnswer = {
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.Answer)
                        isCallActive.value = true
                    },
                    onReject = {
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.Disconnect(DisconnectCause(DisconnectCause.REJECTED)))
                        onCallFinished()
                    }
                )
            } else {
                // Otherwise, ongoing call actions (mute, hold, dtmf, end)
                OngoingCallActions(
                    isMuted = isMuted,
                    isOnHold = isOnHold,
                    isSpeakerOn = isSpeakerOn,
                    onMuteToggle = {
                        isMuted = !isMuted
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.ToggleMute(isMuted))
                    },
                    onHoldToggle = {
                        isOnHold = !isOnHold
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.Hold)
                    },
                    onSpeakerToggle = {
                        isSpeakerOn = !isSpeakerOn
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.ToggleSpeaker(isSpeakerOn))
                    },
                    onSendDtmf = { digit ->
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.DTMF(digit))
                    },
                    onEndCall = {
                        val call = repository.getCurrentRegisteredCall()
                        call?.processAction(TelecomCallAction.Disconnect(DisconnectCause(DisconnectCause.LOCAL)))
                        onCallFinished()
                    }
                )
            }
        }
    }
}

/**
 * Displays basic call info (displayName, phoneNumber), plus a simple call timer
 * if isActive == true. Also shows the Telnyx callState if desired.
 */
@SuppressLint("DefaultLocale")
@Composable
private fun CallInfoSection(
    displayName: String,
    phoneNumber: String,
    isActive: Boolean,
    elapsedMs: Long,
    telnyxCallState: CallState
) {
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.45f)
            .padding(top = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Icon + name + phone
        Image(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            modifier = Modifier.size(80.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = phoneNumber,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))

        if (isActive) {
            // Show a call timer
            val totalSec = elapsedMs / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            Text(
                text = String.format("Call time: %02d:%02d", min, sec),
                style = MaterialTheme.typography.titleSmall
            )
        } else {
            // For incoming or outgoing before active
            Text(
                text = "State: ${telnyxCallState.name}",
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

/**
 * UI for an incoming call that hasn't been answered yet:
 * - Answer
 * - Reject
 */
@Composable
private fun IncomingCallActions(
    onAnswer: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FloatingActionButton(
            onClick = onReject,
            containerColor = MaterialTheme.colorScheme.error
        ) {
            Icon(
                imageVector = Icons.Rounded.Call,
                contentDescription = "Reject",
                modifier = Modifier.rotate(90f)
            )
        }

        FloatingActionButton(
            onClick = onAnswer,
            containerColor = MaterialTheme.colorScheme.primary
        ) {
            Icon(
                imageVector = Icons.Rounded.Call,
                contentDescription = "Answer"
            )
        }
    }
}

/**
 * Ongoing call controls:
 * - Mute/unmute
 * - Hold/unhold
 * - Speaker
 * - DTMF dial pad
 * - End call
 */
@Composable
private fun OngoingCallActions(
    isMuted: Boolean,
    isOnHold: Boolean,
    isSpeakerOn: Boolean,
    onMuteToggle: () -> Unit,
    onHoldToggle: () -> Unit,
    onSpeakerToggle: () -> Unit,
    onSendDtmf: (String) -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // The row with Mute, Speaker, Hold, plus a DialPad button
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute
            IconToggleButton(
                checked = isMuted,
                onCheckedChange = { onMuteToggle() }
            ) {
                if (isMuted) {
                    Icon(Icons.Rounded.MicOff, contentDescription = "Unmute")
                } else {
                    Icon(Icons.Rounded.Mic, contentDescription = "Mute")
                }
            }

            // Speaker
            IconToggleButton(
                checked = isSpeakerOn,
                onCheckedChange = { onSpeakerToggle() }
            ) {
                if (isSpeakerOn) {
                    Icon(Icons.Rounded.SpeakerPhone, contentDescription = "Disable Speaker")
                } else {
                    Icon(Icons.Rounded.Phone, contentDescription = "Enable Speaker")
                }
            }

            // Hold
            IconToggleButton(
                checked = isOnHold,
                onCheckedChange = { onHoldToggle() }
            ) {
                Icon(Icons.Rounded.PhonePaused, contentDescription = "Hold/Resume")
            }

            // DialPad
            DialPadButton(onSendDtmf)
        }

        // End call
        FloatingActionButton(
            onClick = onEndCall,
            containerColor = MaterialTheme.colorScheme.error
        ) {
            Icon(
                imageVector = Icons.Rounded.Call,
                contentDescription = "End Call",
                modifier = Modifier.rotate(90f)
            )
        }
    }
}

/**
 * A button that toggles a small DTMF dial pad overlay to send dtmf digits.
 */
@Composable
private fun DialPadButton(onSendDtmf: (String) -> Unit) {
    var showDialPad by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = { showDialPad = true }) {
            Text("DTMF")
        }

        if (showDialPad) {
            DtmfDialPad(
                onDigitClick = { digit ->
                    onSendDtmf(digit)
                },
                onClose = {
                    showDialPad = false
                }
            )
        }
    }
}

@Composable
private fun DtmfDialPad(
    onDigitClick: (String) -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        shadowElevation = 2.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Dial Pad", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val layout = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#")
            )
            layout.forEach { row ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { digit ->
                        Button(
                            onClick = { onDigitClick(digit) },
                            shape = CircleShape
                        ) {
                            Text(digit)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(8.dp))
            Button(onClick = { onClose() }) {
                Text("Close Dial Pad")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}