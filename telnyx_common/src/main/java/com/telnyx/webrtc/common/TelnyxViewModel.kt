package com.telnyx.webrtc.common

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.telnyx.webrtc.common.domain.authentication.AuthenticateBySIPCredentials
import com.telnyx.webrtc.common.domain.authentication.AuthenticateByToken
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.verto.receive.AnswerResponse
import com.telnyx.webrtc.sdk.verto.receive.ByeResponse
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.LoginResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.RingingResponse
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber
import util.toCredentialConfig
import java.io.IOException


sealed class TelnyxSocketEvent {
    data object OnClientReady : TelnyxSocketEvent()
    data class OnClientError(val message: String) : TelnyxSocketEvent()
    data class OnIncomingCall(val message: InviteResponse) : TelnyxSocketEvent()
    data class OnCallAnswered(val message: AnswerResponse) : TelnyxSocketEvent()
    data class OnCallEnded(val message: ByeResponse) : TelnyxSocketEvent()
    data class OnRinging(val message: RingingResponse) : TelnyxSocketEvent()
    data object OnMedia : TelnyxSocketEvent()
    data object InitState : TelnyxSocketEvent()

}



class TelnyxViewModel : ViewModel() {

    private val _uiState: MutableStateFlow<TelnyxSocketEvent> =
        MutableStateFlow(TelnyxSocketEvent.InitState)
    val uiState: StateFlow<TelnyxSocketEvent> = _uiState.asStateFlow()

    var fcmToken: String? = null

    private val _profileListState = MutableStateFlow<List<Profile>>(
        emptyList()
    )
    val profileList: StateFlow<List<Profile>> = _profileListState

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?>  = _currentProfile

    fun setCurrentConfig(profile: Profile) {
        _currentProfile.value = profile
    }

    fun setupProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
    }


    fun addProfile(context: Context,profile: Profile) {
        val list = _profileListState.value.toMutableList()
        list.add(profile)
        _profileListState.value = list
        ProfileManager.saveProfile(context,profile)
    }

    fun deleteProfile(context: Context,profile: Profile) {
        profile.sipUsername?.let { ProfileManager.deleteProfileBySipUsername(context, it) }
        profile.sipToken?.let { ProfileManager.deleteProfileBySipUsername(context, it) }
        refreshProfileList(context)
    }

    private fun refreshProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
    }


    fun credentialLogin(
        viewContext: Context,
        profile: Profile,
        txPushMetaData: String?,
        autoLogin: Boolean = true
    ) {
        viewModelScope.launch {
            AuthenticateBySIPCredentials(context = viewContext).invoke(
                profile.toCredentialConfig(fcmToken ?: ""),
                txPushMetaData,
                autoLogin
            ).asFlow().collectLatest { response ->
                handleSocketResponse(response)
            }
        }
    }

    fun initProfile(context: Context) {
        getProfiles(context)
        getFCMToken()
    }

    private fun getProfiles(context: Context) {
        ProfileManager.getProfilesList(context).let {
            _profileListState.value = it
        }
    }

    private fun getFCMToken() {
        var token = ""
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.d("Fetching FCM registration token failed")
                fcmToken = null
            } else if (task.isSuccessful) {
                // Get new FCM registration token
                try {
                    token = task.result.toString()
                } catch (e: IOException) {
                    Timber.d(e)
                }
                Timber.d("FCM TOKEN RECEIVED: $token")
            }
            fcmToken = token
        }
    }

    fun tokenLogin(
        viewContext: Context,
        credentialConfig: TokenConfig,
        txPushMetaData: String?,
        autoLogin: Boolean = true
    ) = AuthenticateByToken(context = viewContext).invoke(
        credentialConfig,
        txPushMetaData,
        autoLogin
    ).asFlow()

    private fun handleSocketResponse(response: SocketResponse<ReceivedMessageBody>) {
        when (response.status) {
            SocketStatus.ESTABLISHED -> {
                Timber.d("OnConMan")
            }

            SocketStatus.MESSAGERECEIVED -> {
                val data = response.data as? ReceivedMessageBody
                when (data?.method) {
                    SocketMethod.CLIENT_READY.methodName -> {
                        Log.d("TelnyxViewModel", "Client Ready")
                        Timber.d("You are ready to make calls.")
                        _uiState.value =
                            TelnyxSocketEvent.OnClientReady
                    }

                    SocketMethod.LOGIN.methodName -> {
                        // Use Client Ready
                        val sessionId = (data.result as LoginResponse).sessid
                        sessionId.let {
                            Timber.d("Session ID: $sessionId")
                        }
                    }

                    SocketMethod.INVITE.methodName -> {
                        val inviteResponse = data.result as InviteResponse
                        _uiState.value = TelnyxSocketEvent.OnIncomingCall(inviteResponse)

                    }

                    SocketMethod.ANSWER.methodName -> {
                        _uiState.value =
                            TelnyxSocketEvent.OnCallAnswered(data.result as AnswerResponse)
                    }

                    SocketMethod.RINGING.methodName -> {
                        // Client can simulate ringing state
                        _uiState.value = TelnyxSocketEvent.OnRinging(data.result as RingingResponse)
                    }

                    SocketMethod.MEDIA.methodName -> {
                        // Ringback tone is streamed to the caller
                        // Early media - client can simulate ringing state
                        _uiState.value = TelnyxSocketEvent.OnMedia
                    }

                    SocketMethod.BYE.methodName -> {
                        _uiState.value = TelnyxSocketEvent.OnCallEnded(data.result as ByeResponse)
                    }
                }
            }

            SocketStatus.LOADING -> {
                Timber.i("Loading...")
            }

            SocketStatus.ERROR -> {

            }

            SocketStatus.DISCONNECT -> {

            }
        }
    }

}
