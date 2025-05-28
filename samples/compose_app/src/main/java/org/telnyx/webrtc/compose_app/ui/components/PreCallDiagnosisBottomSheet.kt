package org.telnyx.webrtc.compose_app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telnyx.webrtc.common.TelnyxPrecallDiagnosisState
import com.telnyx.webrtc.sdk.stats.PreCallDiagnosisMetrics
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.secondary_background_color
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreCallDiagnosisBottomSheet(
    preCallDiagnosisState: TelnyxPrecallDiagnosisState?,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        modifier = Modifier.fillMaxSize(),
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(Dimens.mediumSpacing)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                RegularText(text = stringResource(R.string.precall_diagnosis_report_title),
                    size = Dimens.textSize16sp,
                    fontWeight = FontWeight.SemiBold)

                Spacer(modifier = Modifier.weight(1f))

                IconButton(onClick = {
                    scope.launch {
                        sheetState.hide()
                    }.invokeOnCompletion {
                        if (!sheetState.isVisible) {
                            onDismiss()
                        }
                    }
                }) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_close),
                        contentDescription = stringResource(id = R.string.close_button_dessc)
                    )
                }
            }

            var preCallDiagnosisResult by remember { mutableStateOf<PreCallDiagnosisMetrics?>(null) }

            val topText = when (preCallDiagnosisState) {
                is TelnyxPrecallDiagnosisState.PrecallDiagnosisStarted -> {
                    stringResource(R.string.precall_diagnosis_processing)
                }
                is TelnyxPrecallDiagnosisState.PrecallDiagnosisCompleted -> {
                    null
                }
                else -> {
                    stringResource(R.string.precall_diagnosis_failed)
                }
            }

            topText?.let {
                RegularText(text = it,
                    size = Dimens.textSize16sp,
                    fontWeight = FontWeight.SemiBold)
            }

            AnimatedContent(preCallDiagnosisState) { callDiagnosisState ->
                when (callDiagnosisState) {
                    is TelnyxPrecallDiagnosisState.PrecallDiagnosisStarted -> {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = telnyxGreen,
                                modifier = Modifier.padding(Dimens.mediumSpacing)
                            )
                        }
                    }
                    is TelnyxPrecallDiagnosisState.PrecallDiagnosisCompleted -> {
                        preCallDiagnosisResult = callDiagnosisState.data
                    }
                    else -> {
                        // Handle other states if needed
                    }
                }
            }

            AnimatedVisibility(visible = (preCallDiagnosisResult != null)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Dimens.smallSpacing)
                ) {

                    RegularText(text = stringResource(R.string.precall_diagnosis_network_quality),
                        size = Dimens.textSize16sp,
                        fontWeight = FontWeight.SemiBold)

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_quality),
                        value = preCallDiagnosisResult?.quality?.name ?: stringResource(R.string.unknown_label)
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_mos),
                        value = String.format("%.2f", preCallDiagnosisResult?.mos ?: 0.0)
                    )

                    RegularText(text = stringResource(R.string.precall_diagnosis_jitter),
                        size = Dimens.textSize16sp,
                        fontWeight = FontWeight.SemiBold)

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_jitter_min),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.jitter?.min?.times(1000) ?: 0.0)
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_jitter_max),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.jitter?.max?.times(1000) ?: 0.0)
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_jitter_avg),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.jitter?.avg?.times(1000) ?: 0.0)
                    )

                    RegularText(text = stringResource(R.string.precall_diagnosis_rtt),
                        size = Dimens.textSize16sp,
                        fontWeight = FontWeight.SemiBold)

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_rtt_min),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.rtt?.min?.times(1000) ?: 0.0)
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_rtt_max),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.rtt?.max?.times(1000) ?: 0.0)
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_rtt_avg),
                        value = String.format("%.2f ms", preCallDiagnosisResult?.rtt?.avg?.times(1000) ?: 0.0)
                    )

                    RegularText(text = stringResource(R.string.precall_diagnosis_session_stats),
                        size = Dimens.textSize16sp,
                        fontWeight = FontWeight.SemiBold)

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_bytes_sent),
                        value = preCallDiagnosisResult?.bytesSent.toString()
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_bytes_received),
                        value = preCallDiagnosisResult?.bytesReceived.toString()
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_packets_sent),
                        value = preCallDiagnosisResult?.packetsSent.toString()
                    )

                    MetricRow(
                        label = stringResource(R.string.precall_diagnosis_packets_received),
                        value = preCallDiagnosisResult?.packetsReceived.toString()
                    )

                    RegularText(text = stringResource(R.string.precall_diagnosis_ice_candidates),
                        size = Dimens.textSize16sp,
                        fontWeight = FontWeight.SemiBold)

                    preCallDiagnosisResult?.iceCandidates?.forEach { iceCandidate ->
                        ICECandidateRow(
                            label = stringResource(R.string.precall_diagnosis_ice_candidate_label) + " ${iceCandidate.id}, ${iceCandidate.transportId}, ${iceCandidate.protocol}, ${iceCandidate.priority}, " +
                                    "${iceCandidate.candidateType}, ${iceCandidate.address}, ${iceCandidate.port}",
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ICECandidateRow(
    label: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Dimens.size4dp))
            .background(secondary_background_color)
            .padding(start = Dimens.mediumPadding, end = Dimens.mediumPadding, top = Dimens.smallPadding, bottom = Dimens.smallPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RegularText(
            modifier = Modifier
                .padding(end = Dimens.smallPadding),
            text = label,
            maxLines = 5)
    }
}
