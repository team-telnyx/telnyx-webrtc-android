package com.telnyx.webrtc.common

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.telnyx.webrtc.common.domain.authentication.AuthenticateBySIPCredentials
import com.telnyx.webrtc.common.domain.authentication.AuthenticateByToken
import com.telnyx.webrtc.common.domain.push.AnswerIncomingPushCall
import com.telnyx.webrtc.common.domain.authentication.Disconnect
import com.telnyx.webrtc.common.domain.call.AcceptCall
import com.telnyx.webrtc.common.domain.call.EndCurrentAndUnholdLast
import com.telnyx.webrtc.common.domain.call.HoldCurrentAndAcceptIncoming
import com.telnyx.webrtc.common.domain.call.HoldUnholdCall
import com.telnyx.webrtc.common.domain.call.OnByeReceived
import com.telnyx.webrtc.common.domain.call.RejectCall
import com.telnyx.webrtc.common.domain.call.SendInvite
import com.telnyx.webrtc.common.domain.push.RejectIncomingPushCall
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.sdk.Call
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
import com.telnyx.webrtc.common.util.toCredentialConfig
import com.telnyx.webrtc.common.util.toTokenConfig
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import kotlinx.coroutines.Job
import java.io.IOException
import java.util.*


sealed class TelnyxSocketEvent {
    data object OnClientReady : TelnyxSocketEvent()
    data class OnClientError(val message: String) : TelnyxSocketEvent()
    data class OnIncomingCall(val message: InviteResponse) : TelnyxSocketEvent()
    data class OnCallAnswered(val callId: UUID) : TelnyxSocketEvent()
    data class OnCallEnded(val message: ByeResponse) : TelnyxSocketEvent()
    data class OnRinging(val message: RingingResponse) : TelnyxSocketEvent()
    data object OnMedia : TelnyxSocketEvent()
    data object InitState : TelnyxSocketEvent()

}

sealed class TelnyxSessionState {
    data class ClientLoggedIn(val message: LoginResponse) : TelnyxSessionState()
    data object ClientDisconnected : TelnyxSessionState()
}


class TelnyxViewModel : ViewModel() {

    private val _uiState: MutableStateFlow<TelnyxSocketEvent> =
        MutableStateFlow(TelnyxSocketEvent.InitState)
    val uiState: StateFlow<TelnyxSocketEvent> = _uiState.asStateFlow()

    private val _sessionsState: MutableStateFlow<TelnyxSessionState> =
        MutableStateFlow(TelnyxSessionState.ClientDisconnected)
    val sessionsState: StateFlow<TelnyxSessionState> = _sessionsState.asStateFlow()

    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var fcmToken: String? = null

    private var serverConfiguration = TxServerConfiguration()

    private val _profileListState = MutableStateFlow<List<Profile>>(
        emptyList()
    )
    val profileList: StateFlow<List<Profile>> = _profileListState

    val currentCall: Call?
        get() = TelnyxCommon.getInstance().currentCall

    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?> = _currentProfile

    private var notificationAcceptHandlingUUID: UUID? = null

    private var userSessionJob: Job? = null

    fun stopLoading() {
        _isLoading.value = false
    }

    fun changeServerConfigEnvironment(isDev: Boolean) {
        serverConfiguration = serverConfiguration.copy(
            host = if (isDev) {
                "rtcdev.telnyx.com"
            } else {
                "rtc.telnyx.com"
            }
        )
    }

    fun setCurrentConfig(context: Context, profile: Profile) {
        _currentProfile.value = profile
        ProfileManager.saveProfile(context, profile)
    }

    fun setupProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
    }

    fun addProfile(context: Context, profile: Profile) {
        ProfileManager.saveProfile(context, profile)
        refreshProfileList(context)
    }

    fun deleteProfile(context: Context, profile: Profile) {
        profile.sipUsername?.let { ProfileManager.deleteProfileBySipUsername(context, it) }
        profile.sipToken?.let { ProfileManager.deleteProfileBySipToken(context, it) }
        refreshProfileList(context)
    }

    private fun refreshProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
        _currentProfile.value?.let { profile ->
            if (_profileListState.value.firstOrNull { it.callerIdName == profile.callerIdName } == null) {
                _currentProfile.value = null
            }
        }
    }

    fun credentialLogin(
        viewContext: Context,
        profile: Profile,
        txPushMetaData: String?,
        autoLogin: Boolean = true
    ) {
        _isLoading.value = true

        userSessionJob?.cancel()
        userSessionJob = null

        userSessionJob = viewModelScope.launch {
            AuthenticateBySIPCredentials(context = viewContext).invoke(
                serverConfiguration,
                profile.toCredentialConfig(fcmToken ?: ""),
                txPushMetaData,
                autoLogin
            ).asFlow().collectLatest { response ->
                Timber.d("Auth Response: $response")
                handleSocketResponse(response, false)
            }
        }
    }

    fun answerIncomingPushCall(
        viewContext: Context,
        txPushMetaData: String?
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            AnswerIncomingPushCall(context = viewContext)
                .invoke(
                    txPushMetaData,
                    mapOf(Pair("X-test", "123456"))
                ) { answeredCall ->
                    notificationAcceptHandlingUUID = answeredCall.callId
                }
                .asFlow().collectLatest { response ->
                    Timber.d("Auth Response: $response")
                    handleSocketResponse(response, true)
                }
        }
    }

    fun rejectIncomingPushCall(
        viewContext: Context,
        txPushMetaData: String?
    ) {
        _isLoading.value = true
        viewModelScope.launch {
            RejectIncomingPushCall(context = viewContext)
                .invoke(txPushMetaData) {
                    _isLoading.value = false
                }
                .asFlow().collectLatest { response ->
                    Timber.d("Auth Response: $response")
                    handleSocketResponse(response, true)
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
        ProfileManager.getLoggedProfile(context)?.let { profile ->
            _currentProfile.value = profile
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

    fun retrieveFCMToken(): String? {
        return fcmToken
    }

    fun disablePushNotifications(context: Context) {
        TelnyxCommon.getInstance().getTelnyxClient(context).disablePushNotification()
    }

    fun tokenLogin(
        viewContext: Context,
        profile: Profile,
        txPushMetaData: String?,
        autoLogin: Boolean = true
    ) {
        _isLoading.value = true

        userSessionJob?.cancel()
        userSessionJob = null

        userSessionJob = viewModelScope.launch {
            AuthenticateByToken(context = viewContext).invoke(
                serverConfiguration,
                profile.toTokenConfig(fcmToken ?: ""),
                txPushMetaData,
                autoLogin
            ).asFlow().collectLatest { response ->
                Timber.d("Auth Response: $response")
                handleSocketResponse(response, false)
            }
        }
    }

    fun disconnect(viewContext: Context, byUser: Boolean) {
        viewModelScope.launch {
            Disconnect(viewContext).invoke()
            if (byUser)
                _currentProfile.value = null
        }
    }

    fun connectWithLastUsedConfig(viewContext: Context) {
        viewModelScope.launch {
            _currentProfile.value?.let { lastUsedProfile ->
                if (lastUsedProfile.sipToken?.isEmpty() == false) {
                    tokenLogin(
                        viewContext,
                        lastUsedProfile,
                        null,
                        true
                    )
                } else {
                    credentialLogin(
                        viewContext,
                        lastUsedProfile,
                        null,
                        true
                    )
                }
            }
        }
    }

    private fun handleSocketResponse(
        response: SocketResponse<ReceivedMessageBody>,
        isPushConnection: Boolean
    ) {
        when (response.status) {
            SocketStatus.ESTABLISHED -> handleEstablished()
            SocketStatus.MESSAGERECEIVED -> handleMessageReceived(response, isPushConnection)
            SocketStatus.LOADING -> handleLoading()
            SocketStatus.ERROR -> handleError(response)
            SocketStatus.DISCONNECT -> handleDisconnect()
        }
    }

    private fun handleEstablished() {
        Timber.d("OnConMan")
    }

    private fun handleMessageReceived(
        response: SocketResponse<ReceivedMessageBody>,
        isPushConnection: Boolean
    ) {
        val data = response.data as? ReceivedMessageBody
        when (data?.method) {
            SocketMethod.CLIENT_READY.methodName -> handleClientReady()
            SocketMethod.LOGIN.methodName -> handleLogin(data, isPushConnection)
            SocketMethod.INVITE.methodName -> handleInvite(data)
            SocketMethod.ANSWER.methodName -> handleAnswer(data)
            SocketMethod.RINGING.methodName -> handleRinging(data)
            SocketMethod.MEDIA.methodName -> handleMedia()
            SocketMethod.BYE.methodName -> handleBye(data)
        }
    }

    private fun handleClientReady() {
        Log.d("TelnyxViewModel", "Client Ready")
        Timber.d("You are ready to make calls.")
        _uiState.value = TelnyxSocketEvent.OnClientReady
    }

    private fun handleLogin(data: ReceivedMessageBody, isPushConnection: Boolean) {
        val sessionId = (data.result as LoginResponse).sessid
        sessionId.let {
            Timber.d("Session ID: $sessionId")
        }
        _sessionsState.value = TelnyxSessionState.ClientLoggedIn(data.result as LoginResponse)
        _isLoading.value = isPushConnection
    }

    private fun handleInvite(data: ReceivedMessageBody) {
        val inviteResponse = data.result as InviteResponse
        if (notificationAcceptHandlingUUID == inviteResponse.callId) {
            _uiState.value = TelnyxSocketEvent.OnCallAnswered(inviteResponse.callId)
        } else {
            _uiState.value = TelnyxSocketEvent.OnIncomingCall(inviteResponse)
        }
        notificationAcceptHandlingUUID = null
        _isLoading.value = false
    }

    private fun handleAnswer(data: ReceivedMessageBody) {
        _uiState.value = TelnyxSocketEvent.OnCallAnswered((data.result as AnswerResponse).callId)
    }

    private fun handleRinging(data: ReceivedMessageBody) {
        _uiState.value = TelnyxSocketEvent.OnRinging(data.result as RingingResponse)
    }

    private fun handleMedia() {
        _uiState.value = TelnyxSocketEvent.OnMedia
    }

    private fun handleBye(data: ReceivedMessageBody) {
        val byeResponse = data.result as ByeResponse
        viewModelScope.launch {
            OnByeReceived().invoke(byeResponse.callId)
            _uiState.value = currentCall?.let {
                TelnyxSocketEvent.OnCallAnswered(it.callId)
            } ?: TelnyxSocketEvent.OnCallEnded(byeResponse)
        }
    }

    private fun handleLoading() {
        Timber.i("Loading...")
    }

    private fun handleError(response: SocketResponse<ReceivedMessageBody>) {
        _uiState.value = TelnyxSocketEvent.OnClientError(response.errorMessage ?: "An Unknown Error Occurred")
    }

    private fun handleDisconnect() {
        Timber.i("Disconnect...")
        _sessionsState.value = TelnyxSessionState.ClientDisconnected
        _uiState.value = TelnyxSocketEvent.InitState
    }

    fun sendInvite(
        viewContext: Context,
        destinationNumber: String
    ) {
        viewModelScope.launch {
            ProfileManager.getLoggedProfile(viewContext)?.let { currentProfile ->
                Log.d("Call", "clicked profile ${currentProfile.sipUsername}")
                SendInvite(viewContext).invoke(
                    currentProfile.callerIdName ?: "",
                    currentProfile.callerIdNumber ?: "",
                    destinationNumber,
                    "Sample Client State",
                    mapOf(Pair("X-test", "123456"))
                )
            }

        }
    }

    fun endCall(viewContext: Context) {
        viewModelScope.launch {
            currentCall?.let { currentCall ->
                EndCurrentAndUnholdLast(viewContext).invoke(currentCall.callId)
            }
        }
    }

    fun rejectCall(viewContext: Context, callId: UUID) {
        viewModelScope.launch {
            Timber.i("Reject call $callId")
            RejectCall(viewContext).invoke(callId)
        }
    }

    fun answerCall(
        viewContext: Context,
        callId: UUID,
        callerIdNumber: String
    ) {
        viewModelScope.launch {
            currentCall?.let {
                HoldCurrentAndAcceptIncoming(viewContext).invoke(
                    callId,
                    callerIdNumber,
                    mapOf(Pair("X-test", "123456"))
                )
            } ?: run {
                AcceptCall(viewContext).invoke(
                    callId,
                    callerIdNumber,
                    mapOf(Pair("X-test", "123456"))
                )
            }

            _uiState.value =
                TelnyxSocketEvent.OnCallAnswered(callId)
        }
    }

    fun holdUnholdCurrentCall(viewContext: Context) {
        viewModelScope.launch {
            currentCall?.let {
                HoldUnholdCall(viewContext).invoke(it)
            }
        }
    }

    fun dtmfPressed(key: String) {
        currentCall?.let { call ->
            call.dtmf(call.callId, key)
        }
    }
}
