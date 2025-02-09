package org.telnyx.webrtc.compose_app.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.model.Profile
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.telnyx.webrtc.compose_app.R
import org.telnyx.webrtc.compose_app.ui.theme.Dimens
import org.telnyx.webrtc.compose_app.ui.theme.Dimens.shape100Percent
import org.telnyx.webrtc.compose_app.ui.viewcomponents.MediumTextBold
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RegularText
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundSmallButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedOutlinedButton
import org.telnyx.webrtc.compose_app.ui.viewcomponents.RoundedTextButton


@Serializable
object LoginScreenNav
@Serializable
object CallScreenNav

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(telnyxViewModel: TelnyxViewModel) {

    val navController = rememberNavController()

    val sheetState = rememberModalBottomSheetState(true)
    val scope = rememberCoroutineScope()
    var showBottomSheet by remember { mutableStateOf(false) }
    val currentConfig by telnyxViewModel.currentProfile.collectAsState()
    val context = LocalContext.current

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
                    )
                }

                MediumTextBold(text = stringResource(id = R.string.login_info))
                ConnectionState(state = false)
                SessionItem(sessionId = "123456")
            }
        },
        bottomBar = {
            RoundedOutlinedButton(
                text = stringResource(R.string.connect),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (currentConfig != null) {
                    telnyxViewModel.credentialLogin(
                        context,
                        profile = currentConfig!!,
                        txPushMetaData = null
                    )
                } else {
                    Toast.makeText(context, "Please select a profile", Toast.LENGTH_SHORT).show()
                }
            }
        }) {
        Column(
            modifier = Modifier.padding(bottom = it.calculateBottomPadding(), top = it.calculateTopPadding()),
            verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing),
        ) {

            Spacer(modifier = Modifier.size(Dimens.mediumSpacing))

            NavHost(navController = navController, startDestination = CallScreenNav){
                composable<LoginScreenNav> {
                    ProfileSwitcher(profileName = currentConfig?.sipUsername ?: "No Profile") {
                        showBottomSheet = true
                    }
                }
                composable<CallScreenNav> {
                    CallScreen(telnyxViewModel)
                }
            }


        }

        //BottomSheet
        if (showBottomSheet) {
            ModalBottomSheet(
                modifier = Modifier.fillMaxSize(),
                onDismissRequest = {
                    showBottomSheet = false
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
                                    showBottomSheet = false
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

                    AnimatedContent(isAddProfile) { addProfile ->
                        when (addProfile) {
                            true -> {
                                CredentialTokenView(
                                    onSave = { profile ->
                                        profile.apply {
                                            telnyxViewModel.addProfile(context,profile)
                                        }
                                        isAddProfile = !isAddProfile
                                    },
                                    onDismiss = {
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
                                        isAddProfile = !isAddProfile
                                    }
                                    ProfileListView(credentialConfigList, telnyxViewModel)

                                    PosNegButton(
                                        positiveText = stringResource(id = R.string.confirm),
                                        negativeText = stringResource(id = R.string.Cancel),
                                        onPositiveClick = {
                                            scope.launch {
                                                sheetState.hide()
                                            }.invokeOnCompletion {
                                                showBottomSheet = false
                                            }
                                        },
                                        onNegativeClick = {
                                            scope.launch {
                                                sheetState.hide()
                                            }.invokeOnCompletion {
                                                showBottomSheet = false
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

}


@Composable
fun ProfileListView(
    profileList: List<Profile> = emptyList(),
    telnyxViewModel: TelnyxViewModel
) {
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.mediumSpacing)) {

        var currentSipId by remember { mutableStateOf("") }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(Dimens.extraSmallSpacing)) {
            profileList.forEach { item ->
                item {
                    ProfileItem(item, selected = currentSipId == item.sipUsername,
                        onItemSelected = {
                            currentSipId = it.sipUsername ?: ""
                            telnyxViewModel.setCurrentConfig(it)
                        },
                        onEdit = {

                        },
                        onDelete = {

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
        RegularText(text = item.sipUsername, modifier = Modifier.weight(1f))
        IconButton(onClick = {

        }) {
            Icon(imageVector = Icons.Default.Edit, contentDescription = null)
        }

        IconButton(onClick = {

        }) {
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
                modifier = Modifier.height(30.dp),
                backgroundColor = MaterialTheme.colorScheme.secondary,
                text = stringResource(R.string.switch_profile),
                textSize = 8.sp
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
                text = if (state) stringResource(id = R.string.connected) else stringResource(
                    id = R.string.disconnected
                )
            )
        }
    }

}