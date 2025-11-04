package org.telnyx.webrtc.compose_app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.gson.GsonBuilder
import com.telnyx.webrtc.common.TelnyxSessionState
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.model.AudioCodec
import com.telnyx.webrtc.sdk.model.Region
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.ConnectionStatus
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.telnyx.webrtc.compose_app.BuildConfig
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.DroppedIconColor
import org.telnyx.webrtc.compose_app.ui.theme.MainGreen
import org.telnyx.webrtc.compose_app.ui.theme.RingingIconColor
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import org.telnyx.webrtc.compose_app.ui.theme.colorSecondary
import org.telnyx.webrtc.compose_app.ui.theme.secondary_background_color
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.components.ConnectionMetricsDetail
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundSmallButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedOutlinedButton
import org.telnyx.webrtc.compose_app.utils.capitalizeFirstChar
import timber.log.Timber
import org.telnyx.webrtc.compose_app.ui.components.CodecSelectionDialog
import org.telnyx.webrtc.compose_app.ui.components.PreCallDiagnosisBottomSheet
import org.telnyx.webrtc.compose_app.ui.screens.assistant.AssistantLoginBottomSheet

@Serializable
object LoginScreenNav

@Serializable
object CallScreenNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    telnyxViewModel: TelnyxViewModel,
    showWsMessagesBottomSheet: MutableState<Boolean> = remember { mutableStateOf(false) },
    onCopyFcmToken: () -> Unit = {},
    onDisablePushNotifications: () -> Unit = {}
) {
    val loginSheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { false }
    )
    val environmentSheetState = rememberModalBottomSheetState(true, confirmValueChange = { newValue ->
        newValue != SheetValue.Hidden
    })
    val scope = rememberCoroutineScope()
    var showLoginBottomSheet by remember { mutableStateOf(false) }
    var showEnvironmentBottomSheet by remember { mutableStateOf(false) }
    var showPreCallDiagnosisBottomSheet by remember { mutableStateOf(false) }
    var showAssistantLoginBottomSheet by remember { mutableStateOf(false) }
    var showCodecSelectionDialog by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showRegionMenu by remember { mutableStateOf(false) }
    var showConnectionMetrics by remember { mutableStateOf(false) }
    val currentConfig by telnyxViewModel.currentProfile.collectAsState()
    var editableUserProfile by remember { mutableStateOf<Profile?>(null) }
    var selectedUserProfile by remember { mutableStateOf<Profile?>(null) }
    val context = LocalContext.current
    val sessionState by telnyxViewModel.sessionsState.collectAsState()
    val callState by telnyxViewModel.currentCall?.callStateFlow?.collectAsState(initial = CallState.DONE())
        ?: remember { mutableStateOf(CallState.DONE()) }
    val uiState by telnyxViewModel.uiState.collectAsState()
    val isLoading by telnyxViewModel.isLoading.collectAsState()
    val connectionMetrics by telnyxViewModel.connectionMetrics.collectAsState()
    val connectionMetricSheetState = rememberModalBottomSheetState(true)
    val connectionStatus by telnyxViewModel.connectionStatus?.collectAsState(initial = ConnectionStatus.DISCONNECTED)
        ?: remember { mutableStateOf(ConnectionStatus.DISCONNECTED) }

    val missingSessionIdLabel = stringResource(R.string.dash)
    var sessionId by remember { mutableStateOf(missingSessionIdLabel) }

    var showErrorDialog by remember { mutableStateOf(false) }
    var dialogErrorMessage by remember { mutableStateOf<String?>(null) }
    var lastShownErrorMessage by remember { mutableStateOf<String?>(null) }

    val preCallDiagnosisState by telnyxViewModel.precallDiagnosisState.collectAsState()

    var isDebugModeOn by remember { mutableStateOf(telnyxViewModel.debugMode) }

    // Available audio codecs - fetched lazily when dialog opens
    var availableCodecs by remember { mutableStateOf<List<AudioCodec>?>(null) }

    LaunchedEffect(sessionState) {
        sessionId = when (sessionState) {
            is TelnyxSessionState.ClientLoggedIn -> {
                (sessionState as TelnyxSessionState.ClientLoggedIn).message.sessid
            }

            is TelnyxSessionState.ClientDisconnected -> {
                missingSessionIdLabel
            }
        }
    }

    LaunchedEffect(Unit) {
        telnyxViewModel.sessionStateError.collectLatest { errorMessage ->
            errorMessage?.let {
                if (it != lastShownErrorMessage) {
                    dialogErrorMessage = it
                    showErrorDialog = true
                    lastShownErrorMessage = it
                }
                telnyxViewModel.stopLoading()
            }
        }
    }

    if (showErrorDialog) {
        AlertDialog(
            onDismissRequest = { showErrorDialog = false },
            title = { Text(text = stringResource(R.string.error_dialog_title)) },
            text = { dialogErrorMessage?.let { Text(text = it) } },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = false }) {
                    Text(stringResource(id = android.R.string.ok))
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier
            .testTag("homeScreenRoot")
            .padding(start = Dimens.mediumSpacing, end = Dimens.mediumSpacing),
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .padding(vertical = Dimens.largeSpacing),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Empty space on the left to balance the layout
                    Spacer(modifier = Modifier.width(Dimens.extraLargeSpacing))

                    // Logo in the center
                    Image(
                        painter = painterResource(id = R.drawable.telnyx_logo),
                        contentDescription = stringResource(id = R.string.app_name),
                        modifier = Modifier
                            .padding(Dimens.smallPadding)
                            .size(width = 200.dp, height = Dimens.size100dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = {
                                        // Only show environment bottom sheet when not connected
                                        if (connectionStatus == ConnectionStatus.DISCONNECTED) {
                                            showEnvironmentBottomSheet = true
                                        }
                                    }
                                )
                            }
                    )

                    // Menu button on the right (visible for both logged in and non-logged users)
                    Box(
                        modifier = Modifier.width(Dimens.extraLargeSpacing),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.menu),
                                modifier = Modifier.size(Dimens.mediumSpacing)
                            )
                        }

                        // Dropdown menu
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            if (connectionStatus != ConnectionStatus.DISCONNECTED) {
                                // Logged in user options
                                // Websocket Messages option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.websocket_messages)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showWsMessagesBottomSheet.value = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_message),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Copy FCM Token option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.copy_fcm_token_menu)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onCopyFcmToken()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_copy),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Disable Push Notifications option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.disable_push_notifications_menu)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onDisablePushNotifications()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_notifications_off),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Pre-Call Diagnosis option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.precall_diagnosis_button)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showPreCallDiagnosisBottomSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.baseline_call_24),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Prefetch ICE Candidates option
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (telnyxViewModel.prefetchIceCandidate) {
                                                stringResource(R.string.disable_prefetch_ice_candidates)
                                            } else {
                                                stringResource(R.string.enable_prefetch_ice_candidates)
                                            }
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        val newState = !telnyxViewModel.prefetchIceCandidate
                                        telnyxViewModel.prefetchIceCandidate = newState
                                        val message = if (newState) {
                                            context.getString(R.string.enable_prefetch_ice_candidates)
                                        } else {
                                            context.getString(R.string.disable_prefetch_ice_candidates)
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_handshake),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Preferred Codecs option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.preferred_codecs)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showCodecSelectionDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Force ICE Renegotiation option (for testing)
                                DropdownMenuItem(
                                    text = { Text("Force ICE Renegotiation (Test)") },
                                    onClick = {
                                        showOverflowMenu = false
                                        val success = telnyxViewModel.forceIceRenegotiationForTesting()
                                        val message = if (success) {
                                            "ICE renegotiation triggered successfully"
                                        } else {
                                            "Failed to trigger ICE renegotiation - no active call"
                                        }
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_handshake),
                                            contentDescription = null
                                        )
                                    }
                                )
                            } else {
                                // Non-logged user options - only

                                // Region selection
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.region)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showRegionMenu = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_more_vert),
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = {
                                        Text(
                                            text = currentConfig?.region?.displayName
                                                ?: Region.AUTO.displayName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                )

                                // Enable debug option
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (isDebugModeOn) R.string.debug_mode_off else R.string.debug_mode_on)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        isDebugModeOn = !isDebugModeOn
                                        // Update region in current profile or create a default profile
                                        telnyxViewModel.updateDebugMode(isDebugModeOn)
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_debug_on),
                                            contentDescription = null
                                        )
                                    }
                                )

                                // Assistant Login option
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.assistant_login)) },
                                    onClick = {
                                        showOverflowMenu = false
                                        showAssistantLoginBottomSheet = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_login),
                                            contentDescription = null
                                        )
                                    }
                                )
                            }
                        }

                        // Region selection dropdown menu
                        DropdownMenu(
                            expanded = showRegionMenu,
                            onDismissRequest = { showRegionMenu = false }
                        ) {
                            Region.entries.forEach { region ->
                                DropdownMenuItem(
                                    text = { Text(region.displayName) },
                                    onClick = {
                                        showRegionMenu = false
                                        // Update region in current profile or create a default profile
                                        val profile = currentConfig ?: Profile(region = region)
                                        telnyxViewModel.updateRegion(context, region)
                                        if (currentConfig == null) {
                                            telnyxViewModel.setCurrentConfig(
                                                context,
                                                profile.copy(region = region)
                                            )
                                        }
                                    },
                                    leadingIcon = {
                                        if ((currentConfig?.region ?: Region.AUTO) == region) {
                                            Icon(
                                                painter = painterResource(id = R.drawable.baseline_call_24),
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.size(24.dp))
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.spacing16dp))
            }
        },
        bottomBar = {
            if (callState is CallState.DONE || callState is CallState.ERROR)
                BottomBar(
                    state = (connectionStatus != ConnectionStatus.DISCONNECTED),
                    telnyxViewModel,
                    currentConfig
                )

        }) {
        Column(
            modifier = Modifier
                .padding(
                    bottom = it.calculateBottomPadding(),
                    top = it.calculateTopPadding()
                )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingNormal),
        ) {

            MediumTextBold(
                text = if (connectionStatus == ConnectionStatus.CLIENT_READY) stringResource(
                    id = R.string.home_info
                ) else stringResource(id = R.string.login_info)
            )

            ConnectionState(
                connectionStatus = connectionStatus,
                telnyxViewModel = telnyxViewModel,
                showWsMessagesBottomSheet = showWsMessagesBottomSheet,
                connectionMetrics = connectionMetrics,
                onShowConnectionMetrics = { showConnectionMetrics = true }
            )

            if (connectionStatus == ConnectionStatus.CLIENT_READY) {
                CurrentCallState(state = uiState)
            }

            SessionItem(sessionId = sessionId)

            NavHost(navController = navController, startDestination = LoginScreenNav) {
                composable<LoginScreenNav> {
                    ProfileSwitcher(
                        profileName = currentConfig?.callerIdName
                            ?: stringResource(R.string.missing_profile)
                    ) {
                        showLoginBottomSheet = true
                    }
                }
                composable<CallScreenNav> {
                    CallScreen(telnyxViewModel)
                }
            }


        }

        //BottomSheet
        // Pre-call diagnosis bottom sheet
        if (showPreCallDiagnosisBottomSheet) {
            PreCallDiagnosisBottomSheet(
                preCallDiagnosisState = preCallDiagnosisState,
                onDismiss = {
                    showPreCallDiagnosisBottomSheet = false
                }
            )

            // Start the diagnosis call
            LaunchedEffect(Unit) {
                telnyxViewModel.makePreCallDiagnosis(context, BuildConfig.PRECALL_DIAGNOSIS_NUMBER)
            }
        }

        if (showLoginBottomSheet) {
            ModalBottomSheet(
                modifier = Modifier.fillMaxSize(),
                onDismissRequest = {
                    // Intentionally empty - prevents dismissal
                },
                dragHandle = null,
                containerColor = Color.White,
                sheetState = loginSheetState,
                properties = androidx.compose.material3.ModalBottomSheetDefaults.properties(
                    shouldDismissOnBackPress = false
                )
            ) {
                var isAddProfile by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier.padding(Dimens.mediumSpacing),
                    verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)
                ) {

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        MediumTextBold(
                            text = stringResource(id = R.string.existing_profiles),
                            modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                        )
                        /*IconButton(onClick = {
                            scope.launch {
                                sheetState.hide()
                                selectedUserProfile = telnyxViewModel.currentProfile.value
                            }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    showLoginBottomSheet = false
                                }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(id = R.string.close_button_dessc),
                                modifier = Modifier.size(Dimens.size16dp)
                            )
                        }*/
                    }

                    RoundSmallButton(
                        modifier = Modifier
                            .height(Dimens.size32dp)
                            .testTag("addNewProfileButton"),
                        text = stringResource(id = R.string.add_new_profile),
                        textSize = 12.sp,
                        backgroundColor = secondary_background_color,
                        icon = painterResource(R.drawable.ic_add),
                        iconContentDescription = stringResource(R.string.add_new_profile)
                    ) {
                        editableUserProfile = null
                        isAddProfile = !isAddProfile
                    }

                    RegularText(stringResource(R.string.production_label))

                    val credentialConfigList by telnyxViewModel.profileList.collectAsState()

                    Box {

                        Column(verticalArrangement = Arrangement.spacedBy(Dimens.largeSpacing)) {
                            if (credentialConfigList.isNotEmpty()) {
                                ProfileListView(
                                    credentialConfigList,
                                    selectedUserProfile,
                                    onItemSelected = { profile ->
                                        selectedUserProfile = profile
                                    },
                                    onEdit = { profile ->
                                        Timber.d("Edit Profile: $profile")
                                        editableUserProfile = profile
                                        isAddProfile = true
                                    },
                                    onDelete = { profile ->
                                        telnyxViewModel.deleteProfile(context, profile)
                                    })

                            }

                            PosNegButton(
                                positiveText = stringResource(id = R.string.confirm),
                                negativeText = stringResource(id = R.string.Cancel),
                                onPositiveClick = {
                                    scope.launch {
                                        loginSheetState.hide()
                                        selectedUserProfile?.let {
                                            telnyxViewModel.setCurrentConfig(context, it)
                                        }
                                    }.invokeOnCompletion {
                                        showLoginBottomSheet = false
                                    }
                                },
                                onNegativeClick = {
                                    scope.launch {
                                        loginSheetState.hide()
                                        selectedUserProfile = telnyxViewModel.currentProfile.value
                                    }.invokeOnCompletion {
                                        showLoginBottomSheet = false
                                    }
                                })
                        }

                        Column {
                            AnimatedVisibility(
                                visible = isAddProfile,
                                enter = slideInVertically(
                                    initialOffsetY = { fullHeight -> fullHeight }
                                ),
                                exit = slideOutVertically(
                                    targetOffsetY = { fullHeight -> fullHeight },
                                    animationSpec = tween(durationMillis = 150)
                                ),
                                label = "Animated Add Profile"
                            ) {
                                CredentialTokenView(
                                    editableUserProfile,
                                    onSave = { profile ->
                                        profile.apply {
                                            telnyxViewModel.addProfile(context, profile)
                                            editableUserProfile = null
                                        }
                                        selectedUserProfile = profile
                                        isAddProfile = !isAddProfile
                                    },
                                    onDismiss = {
                                        editableUserProfile = null
                                        isAddProfile = !isAddProfile
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    //loading layer
    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colorSecondary.copy(alpha = 0.5f))
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = telnyxGreen,
                trackColor = Color.White
            )
        }
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is TelnyxSocketEvent.OnClientReady -> {
                navController.navigate(CallScreenNav)
            }

            is TelnyxSocketEvent.InitState -> {
                navController.navigate(LoginScreenNav) {
                    popUpTo(CallScreenNav) { inclusive = true }
                }
            }

            else -> {}
        }
    }

    if (showEnvironmentBottomSheet) {
        ModalBottomSheet(
            modifier = Modifier.fillMaxSize(),
            onDismissRequest = {
                showEnvironmentBottomSheet = false
            },
            containerColor = Color.White,
            sheetState = environmentSheetState
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
                        text = stringResource(id = R.string.environment_options),
                        modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            environmentSheetState.hide()
                        }.invokeOnCompletion {
                            if (!environmentSheetState.isVisible) {
                                showEnvironmentBottomSheet = false
                            }
                        }
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_close),
                            contentDescription = stringResource(id = R.string.close_button_dessc)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
                    when (sessionState) {
                        is TelnyxSessionState.ClientLoggedIn -> {
                            RoundSmallButton(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(id = R.string.copy_fcm_token),
                                textSize = 14.sp,
                                backgroundColor = MaterialTheme.colorScheme.secondary
                            ) {
                                val token = telnyxViewModel.retrieveFCMToken()
                                val clipboardManager =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText(
                                    context.getString(R.string.fcm_token_label),
                                    token
                                )
                                clipboardManager.setPrimaryClip(clip)
                            }

                            RoundSmallButton(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(id = R.string.disable_push_notifications),
                                textSize = 14.sp,
                                backgroundColor = MaterialTheme.colorScheme.secondary
                            ) {
                                telnyxViewModel.disablePushNotifications(context)
                                Toast.makeText(
                                    context,
                                    R.string.push_notifications_disabled,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }

                        is TelnyxSessionState.ClientDisconnected -> {
                            RoundSmallButton(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(id = R.string.development_environment),
                                textSize = 14.sp,
                                backgroundColor = MaterialTheme.colorScheme.secondary
                            ) {
                                telnyxViewModel.changeServerConfigEnvironment(isDev = true)
                                Toast.makeText(
                                    context,
                                    R.string.switched_to_development,
                                    Toast.LENGTH_LONG
                                ).show()
                                showEnvironmentBottomSheet = false
                            }

                            RoundSmallButton(
                                modifier = Modifier.fillMaxWidth(),
                                text = stringResource(id = R.string.production_environment),
                                textSize = 14.sp,
                                backgroundColor = MaterialTheme.colorScheme.secondary
                            ) {
                                telnyxViewModel.changeServerConfigEnvironment(isDev = false)
                                Toast.makeText(
                                    context,
                                    R.string.switched_to_production,
                                    Toast.LENGTH_LONG
                                ).show()
                                showEnvironmentBottomSheet = false
                            }
                        }
                    }
                }
            }
        }
    }

    // Assistant Login Bottom Sheet
    if (showAssistantLoginBottomSheet) {
        AssistantLoginBottomSheet(
            telnyxViewModel = telnyxViewModel
        ) {
            showAssistantLoginBottomSheet = false
        }
    }

    // Fetch codecs lazily when dialog opens
    LaunchedEffect(showCodecSelectionDialog) {
        if (showCodecSelectionDialog && availableCodecs == null) {
            availableCodecs = telnyxViewModel.getSupportedAudioCodecs(context)
        }
    }

    // Codec Selection Dialog
    CodecSelectionDialog(
        isVisible = showCodecSelectionDialog,
        availableCodecs = availableCodecs,
        selectedCodecs = telnyxViewModel.getPreferredAudioCodecs() ?: emptyList(),
        onDismiss = {
            showCodecSelectionDialog = false
        },
        onConfirm = { selectedCodecs ->
            telnyxViewModel.setPreferredAudioCodecs(selectedCodecs)
            showCodecSelectionDialog = false
            Toast.makeText(
                context,
                context.getString(
                    R.string.preferred_codecs_updated,
                    selectedCodecs.joinToString(", ") { it.mimeType }),
                Toast.LENGTH_SHORT
            ).show()
        }
    )

    // Connection Metrics Bottom Sheet
    if (showConnectionMetrics) {
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp

        ModalBottomSheet(
            modifier = Modifier.height(screenHeight * 0.8f),
            onDismissRequest = {
                showConnectionMetrics = false
            },
            containerColor = Color.White,
            sheetState = connectionMetricSheetState
        ) {
            Column(
                modifier = Modifier
                    .padding(Dimens.mediumSpacing)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MediumTextBold(
                        text = stringResource(R.string.connection_quality),
                        modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            connectionMetricSheetState.hide()
                        }.invokeOnCompletion {
                            if (!connectionMetricSheetState.isVisible) {
                                showConnectionMetrics = false
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

                Spacer(modifier = Modifier.height(Dimens.spacing8dp))

                // Connection metrics detail
                ConnectionMetricsDetail(
                    connectionMetrics = connectionMetrics,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}


@Composable
fun ProfileListView(
    profileList: List<Profile> = emptyList(),
    selectedProfile: Profile?,
    onItemSelected: (Profile) -> Unit = {},
    onEdit: (Profile) -> Unit = {},
    onDelete: (Profile) -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {

        LazyColumn(
            modifier = Modifier.testTag("profileList"),
            verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)
        ) {
            profileList.forEach { item ->
                item {
                    ProfileItem(
                        item, selected = selectedProfile?.callerIdName == item.callerIdName,
                        onItemSelected = {
                            onItemSelected(item)
                        },
                        onEdit = {
                            onEdit(item)
                        },
                        onDelete = {
                            onDelete(item)
                        }
                    )
                }
            }
        }


    }
}

@Composable
fun PosNegButton(
    positiveText: String,
    negativeText: String,
    positiveTestTag: String = "positiveButton",
    negativeTestTag: String = "negativeButton",
    contentAlignment: Alignment = Alignment.BottomEnd,
    onPositiveClick: () -> Unit = {},
    onNegativeClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = contentAlignment) {

        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundedOutlinedButton(
                modifier = Modifier
                    .height(Dimens.size32dp)
                    .testTag(negativeTestTag),
                text = negativeText,
                contentColor = MaterialTheme.colorScheme.primary,
                backgroundColor = Color.White
            ) {
                onNegativeClick()
            }
            RoundedOutlinedButton(
                modifier = Modifier
                    .height(Dimens.size32dp)
                    .testTag(positiveTestTag),
                text = positiveText
            ) {
                onPositiveClick()
            }

        }
    }
}


@Composable
fun ProfileItem(
    item: Profile,
    selected: Boolean = false,
    onItemSelected: (Profile) -> Unit,
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                onItemSelected(item)
            }
            .padding(horizontal = Dimens.mediumPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RegularText(
            text = item.callerIdName, modifier = Modifier
                .weight(1f)
                .background(color = if (selected) secondary_background_color else Color.Transparent)
                .padding(
                    start = Dimens.spacing8dp,
                    top = Dimens.spacing4dp,
                    end = Dimens.spacing8dp,
                    bottom = Dimens.spacing4dp
                )
        )

        if (selected) {
            IconButton(onClick = onEdit) {
                Icon(painter = painterResource(R.drawable.ic_edit), contentDescription = null)
            }

            IconButton(onClick = onDelete) {
                Icon(painter = painterResource(R.drawable.ic_delete), contentDescription = null)
            }
        }
    }
}


@Composable
fun ProfileSwitcher(profileName: String, onProfileSwitch: () -> Unit = {}) {
    Column {
        RegularText(text = stringResource(id = R.string.profile))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacing12dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegularText(
                text = profileName,
                modifier = Modifier.testTag("profileName")
            )
            RoundSmallButton(
                modifier = Modifier.testTag("switchProfileButton"),
                text = stringResource(R.string.switch_profile),
                textSize = 14.sp,
                backgroundColor = MaterialTheme.colorScheme.background
            ) {
                onProfileSwitch()
            }
        }
    }
}


@Composable
fun SessionItem(sessionId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacing4dp)) {
        RegularText(text = stringResource(id = R.string.session_id))
        RegularText(text = sessionId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionState(
    connectionStatus: ConnectionStatus,
    telnyxViewModel: TelnyxViewModel = viewModel(),
    showWsMessagesBottomSheet: MutableState<Boolean> = remember { mutableStateOf(false) },
    connectionMetrics: SocketConnectionMetrics? = null,
    onShowConnectionMetrics: () -> Unit = {}
) {
    val wsMessages by telnyxViewModel.wsMessages.collectAsState()
    val messageSheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.spacing4dp)
    ) {
        RegularText(text = stringResource(id = R.string.socket))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.size12dp)
                    .background(
                        color = when (connectionStatus) {
                            ConnectionStatus.CONNECTED -> MainGreen
                            ConnectionStatus.CLIENT_READY -> MainGreen
                            ConnectionStatus.RECONNECTING -> RingingIconColor
                            ConnectionStatus.DISCONNECTED -> Color.Red
                        },
                        shape = Dimens.shape100Percent
                    )
            )
            RegularText(
                text = when (connectionStatus) {
                    ConnectionStatus.CONNECTED -> stringResource(R.string.connected)
                    ConnectionStatus.CLIENT_READY -> stringResource(R.string.client_ready)
                    ConnectionStatus.RECONNECTING -> stringResource(R.string.call_state_reconnecting)
                    ConnectionStatus.DISCONNECTED -> stringResource(R.string.disconnected)
                }
            )
            
            if (connectionStatus != ConnectionStatus.DISCONNECTED) {
                // Show info icon for connection details
                IconButton(
                    onClick = { onShowConnectionMetrics() },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_more_vert),
                        contentDescription = stringResource(R.string.connection_details),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
    

    // Websocket messages bottom sheet
    if (showWsMessagesBottomSheet.value) {
        val configuration = LocalConfiguration.current
        val screenHeight = configuration.screenHeightDp.dp

        ModalBottomSheet(
            modifier = Modifier.height(screenHeight * 0.8f),
            onDismissRequest = {
                showWsMessagesBottomSheet.value = false
            },
            containerColor = Color.White,
            sheetState = messageSheetState
        ) {
            Column(
                modifier = Modifier
                    .padding(Dimens.mediumSpacing)
                    .fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    MediumTextBold(
                        text = stringResource(R.string.websocket_messages),
                        modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            messageSheetState.hide()
                        }.invokeOnCompletion {
                            if (!messageSheetState.isVisible) {
                                showWsMessagesBottomSheet.value = false
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

                Spacer(modifier = Modifier.height(Dimens.spacing8dp))

                // Clear messages button
                RoundSmallButton(
                    modifier = Modifier.height(Dimens.size32dp),
                    text = stringResource(R.string.clear_messages),
                    textSize = 12.sp,
                    backgroundColor = secondary_background_color,
                    icon = painterResource(R.drawable.ic_delete),
                    iconContentDescription = stringResource(R.string.clear_messages)
                ) {
                    telnyxViewModel.clearWebsocketMessages()
                }

                Spacer(modifier = Modifier.height(Dimens.spacing16dp))

                // Messages list
                if (wsMessages.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_websocket_messages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(wsMessages.size) { index ->
                            val message = wsMessages[index]
                            val dateFormat = remember {
                                java.text.SimpleDateFormat(
                                    "HH:mm:ss",
                                    java.util.Locale.getDefault()
                                )
                            }

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = Dimens.spacing4dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.small
                                    )
                                    .padding(Dimens.spacing8dp)
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.message_format,
                                        index + 1,
                                        dateFormat.format(message.timestamp)
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(Dimens.spacing4dp))
                                Text(
                                    text = GsonBuilder().setPrettyPrinting().create()
                                        .toJson(message.message),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (index < wsMessages.size - 1) {
                                Spacer(modifier = Modifier.height(Dimens.spacing8dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CurrentCallState(state: TelnyxSocketEvent) {
    val callStateColor = when (state) {
        is TelnyxSocketEvent.OnIncomingCall -> MaterialTheme.colorScheme.tertiary
        is TelnyxSocketEvent.OnCallEnded -> MaterialTheme.colorScheme.tertiary
        is TelnyxSocketEvent.OnRinging -> RingingIconColor
        is TelnyxSocketEvent.OnCallDropped -> DroppedIconColor
        is TelnyxSocketEvent.OnCallReconnecting -> RingingIconColor
        else -> MainGreen
    }

    val callStateName = when (state) {
        is TelnyxSocketEvent.InitState -> stringResource(R.string.call_state_connecting)
        is TelnyxSocketEvent.OnIncomingCall -> stringResource(R.string.call_state_incoming)
        is TelnyxSocketEvent.OnCallEnded -> {
            val cause = state.message?.cause
            if (cause != null) {
                stringResource(R.string.done_with_cause, cause)
            } else {
                stringResource(R.string.done)
            }
        }

        is TelnyxSocketEvent.OnRinging -> stringResource(R.string.call_state_ringing)
        is TelnyxSocketEvent.OnCallDropped -> stringResource(R.string.call_state_dropped)
        is TelnyxSocketEvent.OnCallReconnecting -> stringResource(R.string.call_state_reconnecting)
        else -> stringResource(R.string.call_state_active)
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(Dimens.spacing4dp)
    ) {
        RegularText(text = stringResource(id = R.string.call_state))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.size12dp)
                    .background(
                        color = callStateColor,
                        shape = Dimens.shape100Percent
                    )
            )
            RegularText(
                text = callStateName
            )
        }
    }
}

@Composable
fun BottomBar(
    state: Boolean,
    telnyxViewModel: TelnyxViewModel,
    currentConfig: Profile?
) {
    val context = LocalContext.current
    val selectProfileTitle = stringResource(R.string.please_select_profile)

    Column(
        modifier = Modifier
            .fillMaxHeight(0.16f)
    ) {

        RoundedOutlinedButton(
            text = if (state) stringResource(R.string.disconnect) else stringResource(R.string.connect),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("connectDisconnectButton")
        ) {
            if (state) {
                telnyxViewModel.disconnect(context)
            } else {
                currentConfig?.let { profile ->
                    if (profile.sipToken?.isEmpty() == false) {
                        telnyxViewModel.tokenLogin(
                            context,
                            profile = profile,
                            txPushMetaData = null
                        )
                    } else {
                        telnyxViewModel.credentialLogin(
                            context,
                            profile = profile,
                            txPushMetaData = null
                        )
                    }
                } ?: run {
                    Toast.makeText(context, selectProfileTitle, Toast.LENGTH_SHORT).show()
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = Dimens.mediumPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val environmentLabel = if (telnyxViewModel.serverConfigurationIsDev) {
                stringResource(R.string.development_label)
            } else {
                stringResource(R.string.production_label)
            }.capitalizeFirstChar()!!

            RegularText(
                text = stringResource(
                    R.string.bottom_bar_production_text,
                    environmentLabel,
                    TelnyxClient.SDK_VERSION,
                    BuildConfig.VERSION_NAME
                ),
                color = MaterialTheme.colorScheme.tertiary,
                textAlign = TextAlign.Center
            )
        }
    }

}

@Preview
@Composable
fun HomeScreenPreview() {
    val fakeViewModel = TelnyxViewModel()

    TelnyxAndroidWebRTCSDKTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            innerPadding.calculateTopPadding()
            HomeScreen(rememberNavController(), telnyxViewModel = fakeViewModel)
        }
    }
}
