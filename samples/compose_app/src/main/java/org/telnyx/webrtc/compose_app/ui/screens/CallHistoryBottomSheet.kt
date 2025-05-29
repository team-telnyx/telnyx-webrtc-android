package org.telnyx.webrtc.compose_app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.callRed
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import com.telnyx.webrtc.common.model.CallHistoryItem
import com.telnyx.webrtc.common.model.CallType
import org.telnyx.webrtc.compose_app.utils.Utils
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.telnyx.webrtc.common.TelnyxViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallHistoryBottomSheet(
    telnyxViewModel: TelnyxViewModel,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(true)
    val callHistoryList by telnyxViewModel.callHistoryList.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

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
            Text(
                text = stringResource(R.string.call_history_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = Dimens.spacing8dp)
            )

            if (callHistoryList.isEmpty()) {
                Text(
                    text = stringResource(R.string.call_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(Dimens.spacing16dp)
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(Dimens.spacing8dp),
                    modifier = Modifier.height(400.dp)
                ) {
                    items(callHistoryList.size) { index ->
                        val callItem = callHistoryList[index]
                        CallHistoryItemCompose(
                            callItem = callItem,
                            onCallClick = { number ->
                                telnyxViewModel.sendInvite(context, number, true)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacing16dp))
        }
    }
}

@Composable
fun CallHistoryItemCompose(
    callItem: CallHistoryItem,
    onCallClick: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.spacing16dp)
                .clickable {
                    onCallClick(callItem.destinationNumber)
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(
                    id = when (callItem.callType) {
                        CallType.INBOUND -> R.drawable.baseline_call_received_24
                        CallType.OUTBOUND -> R.drawable.baseline_call_made_24
                    }
                ),
                contentDescription = null,
                tint = when (callItem.callType) {
                    CallType.INBOUND -> callRed
                    CallType.OUTBOUND -> telnyxGreen
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(Dimens.spacing16dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callItem.destinationNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = Utils.formatCallHistoryItemDate(callItem.date),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
} 