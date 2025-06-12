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
import com.telnyx.webrtc.sdk.model.CallNetworkChangeReason
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.IOException
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine


sealed class TelnyxSocketEvent {
    data object OnClientReady : TelnyxSocketEvent()
    data class OnIncomingCall(val message: InviteResponse) : TelnyxSocketEvent()
    data class OnCallAnswered(val callId: UUID) : TelnyxSocketEvent()
    data class OnCallEnded(val message: ByeResponse?) : TelnyxSocketEvent()
    data class OnRinging(val message: RingingResponse) : TelnyxSocketEvent()
    data object OnMedia : TelnyxSocketEvent()
    data object InitState : TelnyxSocketEvent()
    data class OnCallDropped(val reason: CallNetworkChangeReason) : TelnyxSocketEvent()
    data class OnCallReconnecting(val reason: CallNetworkChangeReason) : TelnyxSocketEvent()
}

sealed class TelnyxSessionState {
    data class ClientLoggedIn(val message: LoginResponse) : TelnyxSessionState()
    data object ClientDisconnected : TelnyxSessionState()
}

/**
 * Main ViewModel for interacting with the Telnyx WebRTC SDK.
 *
 * This ViewModel provides methods for authentication, call management, and handling
 * incoming calls. It exposes state flows for observing socket events, session state,
 * and loading state.
 */
class TelnyxViewModel : ViewModel() {

    /**
     * State flow for socket events such as incoming calls, call answered, call ended, etc.
     * Observe this flow to react to call-related events in the UI.
     */
    private val _uiState: MutableStateFlow<TelnyxSocketEvent> =
        MutableStateFlow(TelnyxSocketEvent.InitState)
    val uiState: StateFlow<TelnyxSocketEvent> = _uiState.asStateFlow()

    /**
     * State flow for session events such as login and disconnect.
     * Observe this flow to react to authentication state changes.
     */
    private val _sessionsState: MutableStateFlow<TelnyxSessionState> =
        MutableStateFlow(TelnyxSessionState.ClientDisconnected)
    val sessionsState: StateFlow<TelnyxSessionState> = _sessionsState.asStateFlow()

    /**
     * State flow for socket errors.
     * Observe this flow to react to errors in the socket connection.
     */
    private val _sessionStateError = MutableSharedFlow<String?>()
    val sessionStateError: SharedFlow<String?> = _sessionStateError

    /**
     * State flow for loading state.
     * Observe this flow to show/hide loading indicators in the UI.
     */
    private val _isLoading: MutableStateFlow<Boolean> = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * Firebase Cloud Messaging token for push notifications.
     */
    private var fcmToken: String? = null

    /**
     * Server configuration for the Telnyx WebRTC SDK.
     */
    private var serverConfiguration = TxServerConfiguration()

    /**
     * Flag indicating whether the server configuration is in development environment.
     */
    var serverConfigurationIsDev = false
        private set

    /**
     * State flow for the list of user profiles.
     * Observe this flow to display the list of profiles in the UI.
     */
    private val _profileListState = MutableStateFlow<List<Profile>>(
        emptyList()
    )
    val profileList: StateFlow<List<Profile>> = _profileListState

    /**
     * The current active call, if any.
     * Returns null if there is no active call.
     */
    val currentCall: Call?
        get() = TelnyxCommon.getInstance().currentCall

    /**
     * State flow for the current user profile.
     * Observe this flow to react to profile changes.
     */
    private val _currentProfile = MutableStateFlow<Profile?>(null)
    val currentProfile: StateFlow<Profile?> = _currentProfile

    /**
     * UUID for handling notification acceptance.
     */
    private var notificationAcceptHandlingUUID: UUID? = null

    /**
     * Coroutine job for user session operations.
     */
    private var userSessionJob: Job? = null

    /**
     * Coroutine job for call state operations.
     */
    private var callStateJob: Job? = null

    /**
     * Flag to prevent handling multiple responses simultaneously.
     */
    private var handlingResponses = false

    /**
     * State flow for inbound audio levels.
     */
    private val _inboundAudioLevels = MutableStateFlow<List<Float>>(emptyList())
    val inboundAudioLevels: StateFlow<List<Float>> = _inboundAudioLevels.asStateFlow()

    /**
     * State flow for outbound audio levels.
     */
    private val _outboundAudioLevels = MutableStateFlow<List<Float>>(emptyList())
    val outboundAudioLevels: StateFlow<List<Float>> = _outboundAudioLevels.asStateFlow()

    /**
     * Job for collecting audio levels.
     */
    private var audioLevelCollectorJob: Job? = null

    /**
     * Stops the loading indicator.
     */
    fun stopLoading() {
        _isLoading.value = false
    }

    /**
     * Changes the server configuration environment.
     *
     * @param isDev If true, uses the development environment; otherwise, uses production.
     */
    fun changeServerConfigEnvironment(isDev: Boolean) {
        serverConfigurationIsDev = isDev
        serverConfiguration = serverConfiguration.copy(
            host = if (isDev) {
                "rtcdev.telnyx.com"
            } else {
                "rtc.telnyx.com"
            }
        )
    }

    /**
     * Sets the current user profile configuration. (A profile can be Token or Credential based)
     *
     * @param context The application context.
     * @param profile The user profile to set as current.
     */
    fun setCurrentConfig(context: Context, profile: Profile) {
        _currentProfile.value = profile
        ProfileManager.saveProfile(context, profile)
    }

    /**
     * Initializes the profile list from storage.
     *
     * @param context The application context.
     */
    fun setupProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
    }

    /**
     * Adds a new user profile to storage.
     *
     * @param context The application context.
     * @param profile The user profile to add.
     */
    fun addProfile(context: Context, profile: Profile) {
        ProfileManager.saveProfile(context, profile)
        refreshProfileList(context)
    }

    /**
     * Deletes a user profile from storage.
     *
     * @param context The application context.
     * @param profile The user profile to delete.
     */
    fun deleteProfile(context: Context, profile: Profile) {
        profile.sipUsername?.let { ProfileManager.deleteProfileBySipUsername(context, it) }
        profile.sipToken?.let { ProfileManager.deleteProfileBySipToken(context, it) }
        refreshProfileList(context)
    }

    /**
     * Refreshes the profile list from storage and updates the current profile if needed.
     *
     * @param context The application context.
     */
    private fun refreshProfileList(context: Context) {
        _profileListState.value = ProfileManager.getProfilesList(context)
        _currentProfile.value?.let { profile ->
            if (_profileListState.value.firstOrNull { it.callerIdName == profile.callerIdName } == null) {
                _currentProfile.value = null
            }
        }
    }

    /**
     * Authenticates a user using SIP credentials (username/password).
     *
     * @param viewContext The application context.
     * @param profile The user profile (SIP or Generated Credentials or Token based authentication profile)
     * @param txPushMetaData Optional push metadata for handling incoming calls. PushMetadata is provided by a call notification and is required when logging in to the socket to receive the invitation after connecting to the socket again
     * @param autoLogin Whether to automatically login after authentication.
     */
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
            // Ensure the token is fetched before proceeding
            if (fcmToken == null) {
                fcmToken = getFCMToken()
            }

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

    /**
     * Answers an incoming call received via push notification.
     *
     * @param viewContext The application context.
     * @param txPushMetaData Optional push metadata for handling incoming calls. PushMetadata is provided by a call notification and is required when logging in to the socket to receive the invitation after connecting to the socket again
     */
    fun answerIncomingPushCall(
        viewContext: Context,
        txPushMetaData: String?,
        debug: Boolean
    ) {
        _isLoading.value = true
        TelnyxCommon.getInstance().setHandlingPush(true)
        viewModelScope.launch {
            AnswerIncomingPushCall(context = viewContext)
                .invoke(
                    txPushMetaData,
                    mapOf(Pair("X-test", "123456")),
                    debug
                ) { answeredCall ->
                    notificationAcceptHandlingUUID = answeredCall.callId
                }
                .asFlow().collectLatest { response ->
                    Timber.d("Answering income push response: $response")
                    handleSocketResponse(response, true)
                }
        }

        if (debug) {
            collectAudioLevels()
        }
    }

    /**
     * Initializes the user profile and FCM token.
     *
     * @param context The application context.
     */
    suspend fun initProfile(context: Context) {
        getProfiles(context)
        getFCMToken()
    }

    /**
     * Loads user profiles from storage.
     *
     * @param context The application context.
     */
    private fun getProfiles(context: Context) {
        ProfileManager.getProfilesList(context).let {
            _profileListState.value = it
        }
        ProfileManager.getLoggedProfile(context)?.let { profile ->
            _currentProfile.value = profile
        }
    }

    /**
     * Fetches the Firebase Cloud Messaging token for push notifications.
     *
     * @return The FCM token, or null if fetching failed.
     */
    private suspend fun getFCMToken(): String? = suspendCoroutine { continuation ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.d("Fetching FCM registration token failed")
                fcmToken = null
                continuation.resume(null)
            } else {
                // Get new FCM registration token
                try {
                    val token = task.result.toString()
                    Timber.d("FCM TOKEN RECEIVED: $token")
                    fcmToken = token
                    continuation.resume(token)
                } catch (e: IOException) {
                    Timber.d(e)
                    continuation.resume(null)
                }
            }
        }
    }

    /**
     * Retrieves the current FCM token.
     *
     * @return The current FCM token, or null if not available.
     */
    fun retrieveFCMToken(): String? {
        return fcmToken
    }

    /**
     * Disables push notifications for the current device.
     *
     * @param context The application context.
     */
    fun disablePushNotifications(context: Context) {
        TelnyxCommon.getInstance().getTelnyxClient(context).disablePushNotification()
    }

    /**
     * Authenticates a user using a SIP token.
     *
     * @param viewContext The application context.
     * @param profile The user profile containing the SIP token.
     * @param txPushMetaData Optional push metadata for handling incoming calls. PushMetadata is provided by a call notification and is required when logging in to the socket to receive the invitation after connecting to the socket again
     * @param autoLogin Whether to automatically login after authentication.
     */
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

    /**
     * State flow for call quality metrics of the current call, observed from TelnyxCommon.
     * Observe this flow to display real-time call quality metrics in the UI.
     */
    val callQualityMetrics: StateFlow<CallQualityMetrics?> =
        TelnyxCommon.getInstance().callQualityMetrics

    /**
     * Disconnects the current session.
     *
     * This method will only disconnect if there is no active call and we're not handling
     * a push notification. If there is an active call, the disconnect will be prevented.
     *
     * @param viewContext The application context.
     */
    fun disconnect(viewContext: Context) {
        viewModelScope.launch {
            // Check if we are on a call and not handling a push notification before disconnecting
            // (we check this because clicking on a notification can trigger a disconnect if we are using onStop in MainActivity)
            if (currentCall == null && !TelnyxCommon.getInstance().handlingPush) {
                // No active call, safe to disconnect
                Disconnect(viewContext).invoke()
                // if we are disconnecting, there is no call so we should stop service if one is running
                TelnyxCommon.getInstance().stopCallService(viewContext)
            } else {
                // We have an active call, don't disconnect
                Timber.d("Socket disconnect prevented: Active call in progress")
                TelnyxCommon.getInstance().setHandlingPush(false)
            }
        }
    }

    /**
     * Connects using the last used profile configuration.
     *
     * This method will automatically choose between token and credential login
     * based on the available information in the last used profile.
     *
     * @param viewContext The application context.
     * @param txPushMetaData Optional push metadata for handling incoming calls. PushMetadata is provided by a call notification and is required when logging in to the socket to receive the invitation after connecting to the socket again
     */
    fun connectWithLastUsedConfig(viewContext: Context, txPushMetaData: String? = null) {
        viewModelScope.launch {
            _currentProfile.value?.let { lastUsedProfile ->
                if (lastUsedProfile.sipToken?.isEmpty() == false) {
                    tokenLogin(
                        viewContext,
                        lastUsedProfile,
                        txPushMetaData,
                        true
                    )
                } else {
                    credentialLogin(
                        viewContext,
                        lastUsedProfile,
                        txPushMetaData,
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
        if (handlingResponses) {
            return
        }
        when (response.status) {
            SocketStatus.ESTABLISHED -> handleEstablished()
            SocketStatus.MESSAGERECEIVED -> handleMessageReceived(response, isPushConnection)
            SocketStatus.LOADING -> handleLoading()
            SocketStatus.ERROR -> handleError(response)
            SocketStatus.DISCONNECT -> handleDisconnect()
        }
    }

    private fun handleEstablished() {
        Timber.d("Socket connection established")
    }

    private fun handleMessageReceived(
        response: SocketResponse<ReceivedMessageBody>,
        isPushConnection: Boolean
    ) {
        val data = response.data
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
            val context = TelnyxCommon.getInstance().telnyxClient?.context
            context?.let {
                OnByeReceived().invoke(context, byeResponse.callId)
            }

            // If we are handling a push notification, set the flag to false
            TelnyxCommon.getInstance().setHandlingPush(false)

            _uiState.value = currentCall?.let {
                TelnyxSocketEvent.OnCallAnswered(it.callId)
            } ?: TelnyxSocketEvent.OnCallEnded(byeResponse)
        }
    }

    private fun handleLoading() {
        Timber.i("Loading...")
    }

    /**
     * Handles an error response from the socket.
     * It will navigate to the login screen only in two cases:
     * - there is an error and there is not active call
     * - there is an error during active call. This is happening after unsuccessfully reconnection.
     * @param response The error response to handle.
     */
    private fun handleError(response: SocketResponse<ReceivedMessageBody>) {
        if (currentCall == null || currentCall?.callStateFlow?.value == CallState.ERROR) {
            _sessionsState.value = TelnyxSessionState.ClientDisconnected
            _uiState.value = TelnyxSocketEvent.InitState
        }

        viewModelScope.launch {
            _sessionStateError.emit(response.errorMessage ?: "An Unknown Error Occurred")
        }

        _isLoading.value = false
    }

    private fun handleDisconnect() {
        Timber.i("Disconnect...")
        _sessionsState.value = TelnyxSessionState.ClientDisconnected
        _uiState.value = TelnyxSocketEvent.InitState
    }

    private fun handleCallState(callState: CallState) {
        when (callState) {
            is CallState.ACTIVE -> {
                _uiState.value =
                    TelnyxSocketEvent.OnCallAnswered(currentCall?.callId ?: UUID.randomUUID())
            }

            is CallState.DROPPED -> {
                _uiState.value = TelnyxSocketEvent.OnCallDropped(callState.callNetworkChangeReason)
            }

            is CallState.RECONNECTING -> {
                _uiState.value = TelnyxSocketEvent.OnCallReconnecting(callState.callNetworkChangeReason)
            }

            is CallState.DONE -> {
                _uiState.value = TelnyxSocketEvent.OnCallEnded(null)
            }

            is CallState.ERROR -> {
                _uiState.value = TelnyxSocketEvent.OnCallEnded(null)
            }

            CallState.NEW, CallState.CONNECTING, CallState.RINGING, CallState.HELD -> {
                Timber.d("Call state updated to: %s", callState.javaClass.simpleName)
            }
        }
    }

    /**
     * Initiates an outgoing call to the specified destination number.
     *
     * @param viewContext The application context.
     * @param destinationNumber The phone number to call.
     * @param debug Whether to enable debug mode for call quality metrics.
     */
    fun sendInvite(
        viewContext: Context,
        destinationNumber: String,
        debug: Boolean
    ) {

        callStateJob?.cancel()
        callStateJob = null

        callStateJob = viewModelScope.launch {
            ProfileManager.getLoggedProfile(viewContext)?.let { currentProfile ->
                Log.d("Call", "clicked profile ${currentProfile.sipUsername}")
                SendInvite(viewContext).invoke(
                    currentProfile.callerIdName ?: "",
                    currentProfile.callerIdNumber ?: "",
                    destinationNumber,
                    "Sample Client State",
                    mapOf(Pair("X-test", "123456")),
                    debug
                ).callStateFlow.collect {
                    handleCallState(it)
                }
            }
        }

        if (debug) {
            collectAudioLevels()
        }
    }

    /**
     * Ends the current active call.
     *
     * If there was a previous call on hold, it will be automatically unholded.
     *
     * @param viewContext The application context.
     */
    fun endCall(viewContext: Context) {
        viewModelScope.launch {
            currentCall?.let { currentCall ->
                EndCurrentAndUnholdLast(viewContext).invoke(currentCall.callId)
                // If we are handling a push notification, set the flag to false
                TelnyxCommon.getInstance().setHandlingPush(false)
            }
        }
    }

    /**
     * Rejects an incoming call.
     *
     * @param viewContext The application context.
     * @param callId The UUID of the call to reject.
     */
    fun rejectCall(viewContext: Context, callId: UUID) {
        viewModelScope.launch {
            Timber.i("Reject call $callId")
            RejectCall(viewContext).invoke(callId)
            // If we are handling a push notification, set the flag to false
            TelnyxCommon.getInstance().setHandlingPush(false)
        }
    }

    /**
     * Answers an incoming call.
     *
     * If there is already an active call, the current call will be put on hold
     * before answering the new call.
     *
     * @param viewContext The application context.
     * @param callId The UUID of the call to answer.
     * @param callerIdNumber The caller ID number for the call.
     * @param debug Whether to enable debug mode for call quality metrics.
     */
    fun answerCall(
        viewContext: Context,
        callId: UUID,
        callerIdNumber: String,
        debug: Boolean
    ) {
        callStateJob?.cancel()
        callStateJob = null

        callStateJob = viewModelScope.launch {
            _uiState.value =
                TelnyxSocketEvent.OnCallAnswered(callId)

            currentCall?.let {
                HoldCurrentAndAcceptIncoming(viewContext).invoke(
                    callId,
                    callerIdNumber,
                    mapOf(Pair("X-test", "123456")),
                    debug
                ).callStateFlow.collect {
                    handleCallState(it)
                }
            } ?: run {
                AcceptCall(viewContext).invoke(
                    callId,
                    callerIdNumber,
                    mapOf(Pair("X-test", "123456")),
                    debug
                ).callStateFlow.collect {
                    handleCallState(it)
                }
            }
        }

        if (debug) {
            collectAudioLevels()
        }
    }

    /**
     * Toggles the hold state of the current call.
     *
     * If the call is active, it will be put on hold.
     * If the call is on hold, it will be unholded.
     *
     * @param viewContext The application context.
     */
    fun holdUnholdCurrentCall(viewContext: Context) {
        viewModelScope.launch {
            currentCall?.let {
                HoldUnholdCall(viewContext).invoke(it)
            }
        }
    }

    /**
     * Sends DTMF tones during an active call.
     *
     * @param key The DTMF key to send (0-9, *, #).
     */
    fun dtmfPressed(key: String) {
        currentCall?.let { call ->
            call.dtmf(call.callId, key)
        }
    }

    private fun collectAudioLevels() {
        // Cancel any previous collector job FIRST to avoid multiple collectors
        audioLevelCollectorJob?.cancel()
        audioLevelCollectorJob = viewModelScope.launch {
            Timber.d("Audio level collection started.")
            TelnyxCommon.getInstance().callQualityMetrics.collect { metrics ->
                if (metrics == null) {
                    // Clear levels when call ends or metrics are null
                    if (_inboundAudioLevels.value.isNotEmpty()) {
                        _inboundAudioLevels.value = emptyList()
                    }
                    if (_outboundAudioLevels.value.isNotEmpty()) {
                        _outboundAudioLevels.value = emptyList()
                    }
                    // Cancel the job itself when metrics become null (call ended)
                    audioLevelCollectorJob?.cancel()
                    Timber.d("Audio level collection stopped as call ended.")
                } else {
                    // Update inbound levels
                    val currentInbound = _inboundAudioLevels.value.toMutableList()
                    currentInbound.add(metrics.inboundAudioLevel)
                    while (currentInbound.size > MAX_AUDIO_LEVELS) {
                        currentInbound.removeAt(0)
                    }
                    _inboundAudioLevels.value = currentInbound

                    // Update outbound levels
                    val currentOutbound = _outboundAudioLevels.value.toMutableList()
                    currentOutbound.add(metrics.outboundAudioLevel)
                    while (currentOutbound.size > MAX_AUDIO_LEVELS) {
                        currentOutbound.removeAt(0)
                    }
                    _outboundAudioLevels.value = currentOutbound
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioLevelCollectorJob?.cancel()
        userSessionJob?.cancel()
    }

    companion object {
        private const val MAX_AUDIO_LEVELS = 100
    }
}
