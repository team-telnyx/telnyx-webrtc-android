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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.telnyx.webrtc.compose_app.BuildConfig
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent
import org.telnyx.webrtc.compose_app.ui.theme.DroppedIconColor
import org.telnyx.webrtc.compose_app.ui.theme.MainGreen
import org.telnyx.webrtc.compose_app.ui.theme.RingingIconColor
import org.telnyx.webrtc.compose_app.ui.theme.TelnyxAndroidWebRTCSDKTheme
import org.telnyx.webrtc.compose_app.ui.theme.colorSecondary
import org.telnyx.webrtc.compose_app.ui.theme.secondary_background_color
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundSmallButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedOutlinedButton
import timber.log.Timber

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
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()
    var showLoginBottomSheet by remember { mutableStateOf(false) }
    var showEnvironmentBottomSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    val currentConfig by telnyxViewModel.currentProfile.collectAsState()
    var editableUserProfile by remember { mutableStateOf<Profile?>(null) }
    var selectedUserProfile by remember { mutableStateOf<Profile?>(null) }
    val context = LocalContext.current
    val sessionState by telnyxViewModel.sessionsState.collectAsState()
    val callState by telnyxViewModel.currentCall?.callStateFlow?.collectAsState(initial = CallState.DONE())
        ?: remember { mutableStateOf(CallState.DONE()) }
    val uiState by telnyxViewModel.uiState.collectAsState()
    val isLoading by telnyxViewModel.isLoading.collectAsState()
    val wsMessages by telnyxViewModel.wsMessages.collectAsState()

    val missingSessionIdLabel = stringResource(R.string.dash)
    var sessionId by remember { mutableStateOf(missingSessionIdLabel) }

    var showErrorDialog by remember { mutableStateOf(false) }
    var dialogErrorMessage by remember { mutableStateOf<String?>(null) }
    var lastShownErrorMessage by remember { mutableStateOf<String?>(null) }

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
            title = { Text(text = "Error") },
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
            .padding(start = Dimens.mediumSpacing, end = Dimens.mediumSpacing),
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentHeight(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Empty space on the left to balance the layout
                    Spacer(modifier = Modifier.width(48.dp))
                    
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
                                        // Only show environment bottom sheet when not logged in
                                        if (sessionState is TelnyxSessionState.ClientDisconnected) {
                                            showEnvironmentBottomSheet = true
                                        }
                                    }
                                )
                            }
                    )
                    
                    // Menu button on the right (only visible when logged in)
                    Box(
                        modifier = Modifier.width(48.dp),
                        contentAlignment = Alignment.CenterEnd
                    ) {
                        if (sessionState !is TelnyxSessionState.ClientDisconnected) {
                            IconButton(onClick = { showOverflowMenu = true }) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_more_vert),
                                    contentDescription = "Menu",
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            
                            // Dropdown menu
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                // Websocket Messages option
                                DropdownMenuItem(
                                    text = { Text("Websocket Messages") },
                                    onClick = {
                                        showOverflowMenu = false
                                        showWsMessagesBottomSheet.value = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = painterResource(id = R.drawable.ic_message),
                                            contentDescription = null
                                        )
                                    },
                                    trailingIcon = if (wsMessages.isNotEmpty()) {
                                        {
                                            Text(
                                                text = wsMessages.size.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White,
                                                modifier = Modifier
                                                    .background(
                                                        color = MaterialTheme.colorScheme.primary,
                                                        shape = shape100Percent
                                                    )
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    } else null
                                )
                                
                                // Copy FCM Token option
                                DropdownMenuItem(
                                    text = { Text("Copy FCM Token") },
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
                                    text = { Text("Disable Push Notifications") },
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
                            }
                        } else {
                            // Empty space when not logged in
                            Spacer(modifier = Modifier.width(48.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.spacing16dp))
            }
        },
        bottomBar = {
            if (callState is CallState.DONE || callState is CallState.ERROR)
            BottomBar(
                state = (sessionState !is TelnyxSessionState.ClientDisconnected),
                telnyxViewModel,
                currentConfig
            )

        }) {
        Column(
            modifier = Modifier.padding(
                bottom = it.calculateBottomPadding(),
                top = it.calculateTopPadding()
            )
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingNormal),
        ) {

            MediumTextBold(text = if (sessionState !is TelnyxSessionState.ClientDisconnected) stringResource(id = R.string.home_info) else stringResource(id = R.string.login_info))

            ConnectionState(
                state = (sessionState !is TelnyxSessionState.ClientDisconnected),
                telnyxViewModel = telnyxViewModel,
                showWsMessagesBottomSheet = showWsMessagesBottomSheet
            )

            if (sessionState !is TelnyxSessionState.ClientDisconnected) {
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
        if (showLoginBottomSheet) {
            ModalBottomSheet(
                modifier = Modifier.fillMaxSize(),
                onDismissRequest = {
                    showLoginBottomSheet = false
                },
                containerColor = Color.White,
                sheetState = sheetState
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
                        IconButton(onClick = {
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
                        }
                    }

                    RoundSmallButton(
                        modifier = Modifier.height(Dimens.size32dp),
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
                                        sheetState.hide()
                                        selectedUserProfile?.let {
                                            telnyxViewModel.setCurrentConfig(context, it)
                                        }
                                    }.invokeOnCompletion {
                                        showLoginBottomSheet = false
                                    }
                                },
                                onNegativeClick = {
                                    scope.launch {
                                        sheetState.hide()
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
                        text = stringResource(id = R.string.environment_options),
                        modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
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
                                val clip = ClipData.newPlainText("FCM Token", token)
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
                    ProfileItem(item, selected = selectedProfile?.callerIdName == item.callerIdName,
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
    contentAlignment:Alignment = Alignment.BottomEnd,
    onPositiveClick: () -> Unit = {},
    onNegativeClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = contentAlignment) {

        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundedOutlinedButton(modifier = Modifier.height(Dimens.size32dp).testTag("negativeButton"),
                text = negativeText,
                contentColor = MaterialTheme.colorScheme.primary,
                backgroundColor = Color.White) {
                onNegativeClick()
            }
            RoundedOutlinedButton(modifier = Modifier.height(Dimens.size32dp).testTag("positiveButton"), text = positiveText) {
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
        RegularText(text = item.callerIdName, modifier = Modifier
            .weight(1f)
            .background(color = if (selected) secondary_background_color else Color.Transparent)
            .padding(start = Dimens.spacing8dp, top = Dimens.spacing4dp, end = Dimens.spacing8dp, bottom = Dimens.spacing4dp)
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
            RegularText(text = profileName)
            RoundSmallButton(
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
    state: Boolean, 
    telnyxViewModel: TelnyxViewModel = viewModel(),
    showWsMessagesBottomSheet: MutableState<Boolean> = remember { mutableStateOf(false) }
) {
    val wsMessages by telnyxViewModel.wsMessages.collectAsState()
    val sheetState = rememberModalBottomSheetState(true)
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
                        color = if (state) MainGreen else Color.Red,
                        shape = shape100Percent
                    )
            )
            RegularText(
                text = stringResource(if (state) R.string.connected else R.string.disconnected)
            )
        }
    }
    
    // Websocket messages bottom sheet
    if (showWsMessagesBottomSheet.value) {
        ModalBottomSheet(
            modifier = Modifier.height(600.dp),
            onDismissRequest = {
                showWsMessagesBottomSheet.value = false
            },
            containerColor = Color.White,
            sheetState = sheetState
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
                        text = "Websocket Messages",
                        modifier = Modifier.fillMaxWidth(fraction = 0.9f)
                    )
                    IconButton(onClick = {
                        scope.launch {
                            sheetState.hide()
                        }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
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
                    text = "Clear Messages",
                    textSize = 12.sp,
                    backgroundColor = secondary_background_color,
                    icon = painterResource(R.drawable.ic_delete),
                    iconContentDescription = "Clear Messages"
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
                            text = "No websocket messages yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(400.dp)
                    ) {
                        items(wsMessages.size) { index ->
                            val message = wsMessages[index]
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
                                    text = "Message ${index + 1}",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(Dimens.spacing4dp))
                                Text(
                                    text = GsonBuilder().setPrettyPrinting().create().toJson(message),
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
                "Done - $cause"
            } else {
                "Done"
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
                        shape = shape100Percent
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

    Column (modifier = Modifier
        .fillMaxHeight(0.16f)) {

        RoundedOutlinedButton(
            text = if (state) stringResource(R.string.disconnect) else stringResource(R.string.connect),
            modifier = Modifier
                .fillMaxWidth()
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
                    Toast.makeText(context, "Please select a profile", Toast.LENGTH_SHORT).show()
                }
            }
        }

        Column (modifier = Modifier
            .fillMaxSize()
            .padding(bottom = Dimens.mediumPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center) {
                val environmentLabel = if (telnyxViewModel.serverConfigurationIsDev) {
                    stringResource(R.string.development_label)
                } else {
                    stringResource(R.string.production_label)
                }.replaceFirstChar { it.uppercaseChar() }

                RegularText(text = stringResource(R.string.bottom_bar_production_text, environmentLabel, TelnyxClient.SDK_VERSION, BuildConfig.VERSION_NAME),
                        color = MaterialTheme.colorScheme.tertiary,
                        textAlign = TextAlign.Center)
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
