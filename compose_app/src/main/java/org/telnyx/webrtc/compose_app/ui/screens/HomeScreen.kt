package org.telnyx.webrtc.compose_app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.telnyx.webrtc.common.TelnyxSessionState
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.model.CallState
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent
import org.telnyx.webrtc.compose_app.ui.theme.colorSecondary
import org.telnyx.webrtc.compose_app.ui.theme.telnyxGreen
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundSmallButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedOutlinedButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedTextButton
import timber.log.Timber

@Serializable
object LoginScreenNav

@Serializable
object CallScreenNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavHostController, telnyxViewModel: TelnyxViewModel) {
    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()
    var showLoginBottomSheet by remember { mutableStateOf(false) }
    var showEnvironmentBottomSheet by remember { mutableStateOf(false) }
    val currentConfig by telnyxViewModel.currentProfile.collectAsState()
    var editableUserProfile by remember { mutableStateOf<Profile?>(null) }
    val context = LocalContext.current
    val sessionState by telnyxViewModel.sessionsState.collectAsState()
    val callState by telnyxViewModel.currentCall?.callStateFlow?.collectAsState()
        ?: remember { mutableStateOf(CallState.DONE) }
    val uiState by telnyxViewModel.uiState.collectAsState()
    val isLoading by telnyxViewModel.isLoading.collectAsState()

    LaunchedEffect(uiState) {
        when (uiState) {
            is TelnyxSocketEvent.OnClientError -> {
                val errorMessage = (uiState as TelnyxSocketEvent.OnClientError).message
                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                telnyxViewModel.stopLoading()
            }

            else -> {}
        }
    }

    Scaffold(modifier = Modifier.padding(Dimens.mediumSpacing),
        topBar = {
            Column(
                verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
            ) {
                Spacer(modifier = Modifier.size(Dimens.mediumSpacing))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.telnyx_logo),
                        contentDescription = stringResource(id = R.string.app_name),
                        modifier = Modifier
                            .padding(Dimens.smallPadding)
                            .size(width = 200.dp, height = Dimens.size100dp)
                            .pointerInput(Unit) {
                                detectTapGestures(
                                    onLongPress = {
                                        showEnvironmentBottomSheet = true
                                    }
                                )
                            }
                    )
                }

                MediumTextBold(text = stringResource(id = R.string.login_info))
                ConnectionState(state = (sessionState is TelnyxSessionState.ClientLoggedIn))
                CurrentCallState(state = callState)
                SessionItem(
                    sessionId = when (sessionState) {
                        is TelnyxSessionState.ClientLoggedIn -> {
                            (sessionState as TelnyxSessionState.ClientLoggedIn).message.sessid
                        }

                        is TelnyxSessionState.ClientDisconnected -> {
                            stringResource(R.string.dash)
                        }
                    }
                )
            }
        },
        bottomBar = {
            ConnectionStateButton(
                state = (sessionState is TelnyxSessionState.ClientLoggedIn),
                telnyxViewModel,
                currentConfig
            )

        }) {
        Column(
            modifier = Modifier.padding(
                bottom = it.calculateBottomPadding(),
                top = it.calculateTopPadding()
            ),
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
        ) {

            Spacer(modifier = Modifier.size(Dimens.mediumSpacing))

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
                            }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    showLoginBottomSheet = false
                                }
                            }
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_close),
                                contentDescription = stringResource(id = R.string.close_button_dessc)
                            )
                        }
                    }

                    val credentialConfigList by telnyxViewModel.profileList.collectAsState()

                    AnimatedContent(isAddProfile, label = "Animated Add Profile") { addProfile ->
                        when (addProfile) {
                            true -> {
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

                            false -> {
                                Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {
                                    RoundSmallButton(
                                        text = stringResource(id = R.string.add_new_profile),
                                        backgroundColor = MaterialTheme.colorScheme.secondary,
                                        modifier = Modifier.height(30.dp),
                                        textSize = 12.sp
                                    ) {
                                        editableUserProfile = null
                                        isAddProfile = !isAddProfile
                                    }
                                    ProfileListView(
                                        credentialConfigList,
                                        telnyxViewModel,
                                        onEdit = { profile ->
                                            Timber.d("Edit Profile: $profile")
                                            editableUserProfile = profile
                                            isAddProfile = true
                                        },
                                        onDelete = { profile ->
                                            telnyxViewModel.deleteProfile(context, profile)
                                        })

                                    PosNegButton(
                                        positiveText = stringResource(id = R.string.confirm),
                                        negativeText = stringResource(id = R.string.Cancel),
                                        onPositiveClick = {
                                            scope.launch {
                                                sheetState.hide()
                                            }.invokeOnCompletion {
                                                showLoginBottomSheet = false
                                            }
                                        },
                                        onNegativeClick = {
                                            scope.launch {
                                                sheetState.hide()
                                            }.invokeOnCompletion {
                                                showLoginBottomSheet = false
                                            }
                                        })
                                }

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
                                text = stringResource(id = R.string.copy_fcm_token),
                                backgroundColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth(),
                                textSize = 14.sp
                            ) {
                                val token = telnyxViewModel.retrieveFCMToken()
                                val clipboardManager =
                                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("FCM Token", token)
                                clipboardManager.setPrimaryClip(clip)
                            }

                            RoundSmallButton(
                                text = stringResource(id = R.string.disable_push_notifications),
                                backgroundColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth(),
                                textSize = 14.sp
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
                                text = stringResource(id = R.string.development_environment),
                                backgroundColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth(),
                                textSize = 14.sp
                            ) {
                                telnyxViewModel.changeServerConfigEnvironment(isDev = true)
                                Toast.makeText(
                                    context,
                                    R.string.switched_to_development,
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            RoundSmallButton(
                                text = stringResource(id = R.string.production_environment),
                                backgroundColor = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.fillMaxWidth(),
                                textSize = 14.sp
                            ) {
                                telnyxViewModel.changeServerConfigEnvironment(isDev = false)
                                Toast.makeText(
                                    context,
                                    R.string.switched_to_production,
                                    Toast.LENGTH_LONG
                                ).show()

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
    telnyxViewModel: TelnyxViewModel,
    onEdit: (Profile) -> Unit = {},
    onDelete: (Profile) -> Unit = {}
) {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {

        var currentSipId by remember { mutableStateOf("") }

        LazyColumn(
            modifier = Modifier.testTag("profileList"),
            verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)
        ) {
            profileList.forEach { item ->
                item {
                    ProfileItem(item, selected = currentSipId == item.callerIdName,
                        onItemSelected = {
                            currentSipId = it.callerIdName ?: ""
                            telnyxViewModel.setCurrentConfig(context, it)
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
    onPositiveClick: () -> Unit = {},
    onNegativeClick: () -> Unit = {}
) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {

        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RoundedTextButton(text = negativeText) {
                onNegativeClick()
            }
            RoundedOutlinedButton(text = positiveText) {
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
            .background(color = if (selected) MaterialTheme.colorScheme.background else Color.Transparent)
            .clickable {
                onItemSelected(item)
            }
            .padding(horizontal = Dimens.mediumPadding),
        horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Person,
            contentDescription = stringResource(id = R.string.profile)
        )
        RegularText(text = item.callerIdName, modifier = Modifier.weight(1f))
        IconButton(onClick = onEdit) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = null)
        }

        IconButton(onClick = onDelete) {
            Icon(imageVector = Icons.Default.Delete, contentDescription = null)
        }
    }
}


@Composable
fun ProfileSwitcher(profileName: String, onProfileSwitch: () -> Unit = {}) {
    Column {
        RegularText(text = stringResource(id = R.string.profile))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RegularText(text = profileName)
            RoundSmallButton(
                backgroundColor = MaterialTheme.colorScheme.secondary,
                text = stringResource(R.string.switch_profile),
                textSize = 14.sp
            ) {
                onProfileSwitch()
            }
        }
    }
}


@Composable
fun SessionItem(sessionId: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)) {
        RegularText(text = stringResource(id = R.string.session_id))
        RegularText(text = sessionId)
    }
}

@Composable
fun ConnectionState(state: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)) {
        RegularText(text = stringResource(id = R.string.socket))
        Row(
            horizontalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(Dimens.size12dp)
                    .background(
                        color = if (state) Color.Green else Color.Red,
                        shape = shape100Percent
                    )
            )
            RegularText(
                text = if (state) stringResource(id = R.string.client_ready) else stringResource(
                    id = R.string.disconnected
                )
            )
        }
    }
}

@Composable
fun CurrentCallState(state: CallState) {
    if (state == CallState.DONE) return
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)) {
        RegularText(text = stringResource(id = R.string.call_state))
        RegularText(text = state.name)
    }
}

@Composable
fun ConnectionStateButton(
    state: Boolean,
    telnyxViewModel: TelnyxViewModel,
    currentConfig: Profile?
) {
    val context = LocalContext.current
    RoundedOutlinedButton(
        text = if (state) stringResource(R.string.disconnect) else stringResource(R.string.connect),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state) {
            telnyxViewModel.disconnect(context, true)
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
}
