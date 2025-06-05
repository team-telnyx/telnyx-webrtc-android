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
import com.telnyx.webrtc.sdk.model.Region
import com.telnyx.webrtc.common.data.CallHistoryRepository
import com.telnyx.webrtc.common.model.CallHistoryItem
import com.telnyx.webrtc.common.model.CallType
import com.telnyx.webrtc.common.model.WebsocketMessage
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
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.common.model.MetricSummary
import com.telnyx.webrtc.common.model.PreCallDiagnosis
import com.telnyx.webrtc.sdk.model.SocketError
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

sealed class TelnyxPrecallDiagnosisState {
    data object PrecallDiagnosisStarted : TelnyxPrecallDiagnosisState()
    data class PrecallDiagnosisCompleted(val data: PreCallDiagnosis) : TelnyxPrecallDiagnosisState()
    data object PrecallDiagnosisFailed : TelnyxPrecallDiagnosisState()
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
     * State flow for websocket messages.
     * Observe this flow to display websocket messages in the UI.
     */
    private val _wsMessages = MutableStateFlow<List<WebsocketMessage>>(emptyList())
    val wsMessages: StateFlow<List<WebsocketMessage>> = _wsMessages.asStateFlow()

    /**
     * Job for collecting audio levels.
     */
    private var audioLevelCollectorJob: Job? = null

    /**
     * Job for collecting websocket messages.
     */
    private var wsMessagesCollectorJob: Job? = null

    /**
     * Call history repository for managing call history data.
     */
    private var callHistoryRepository: CallHistoryRepository? = null

    /**
     * State flow for call history list.
     * Observe this flow to display call history in the UI.
     */
    private val _callHistoryList = MutableStateFlow<List<CallHistoryItem>>(emptyList())
    val callHistoryList: StateFlow<List<CallHistoryItem>> = _callHistoryList.asStateFlow()

     /**
      * State flow for precall diagnosis results.
     */
    private val _precallDiagnosisState = MutableStateFlow<TelnyxPrecallDiagnosisState?>(null)
    var precallDiagnosisState: StateFlow<TelnyxPrecallDiagnosisState?> = _precallDiagnosisState.asStateFlow()

    /**
     * Job for collecting precall diagnosis metrics.
     */
    private var precallDiagnosisCollectorJob: Job? = null

    /**
     * Precall diagnosis data.
     */
    private var precallDiagnosisData: MutableList<CallQualityMetrics>? = null

    /**
     * Flag indicating whether the socket has disconnected by user action.
     */
    private var disconnectedByUser = false

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
        loadCallHistoryForCurrentProfile()
    }

    /**
     * Updates the region for the current profile.
     *
     * @param context The application context.
     * @param region The selected region.
     */
    fun updateRegion(context: Context, region: Region) {
        _currentProfile.value?.let { profile ->
            val updatedProfile = profile.copy(region = region)
            _currentProfile.value = updatedProfile
            ProfileManager.saveProfile(context, updatedProfile)
        }
    }

    /**
     * Updates the debug mode for the current profile.
     *
     * @param context The application context.
     * @param debugMode The debug mode state.
     */
    fun updateDebugMode(context: Context, debugMode: Boolean) {
        _currentProfile.value?.let { profile ->
            val updatedProfile = profile.copy(isDebug = debugMode)
            _currentProfile.value = updatedProfile
            ProfileManager.saveProfile(context, updatedProfile)
        }
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
        viewModelScope.launch {
            deleteCallHistoryForProfile(profile.callerIdName ?: "Unknown")
        }
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
        disconnectedByUser = false

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
        disconnectedByUser = false
        TelnyxCommon.getInstance().setHandlingPush(true)

        userSessionJob?.cancel()
        userSessionJob = null


        userSessionJob = viewModelScope.launch {
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
     * Rejects an incoming call received via push notification.
     *
     * @param viewContext The application context.
     * @param txPushMetaData Optional push metadata for handling incoming calls. PushMetadata is provided by a call notification and is required when logging in to the socket to receive the invitation after connecting to the socket again
     */
    fun rejectIncomingPushCall(
        viewContext: Context,
        txPushMetaData: String?
    ) {
        _isLoading.value = true
        disconnectedByUser = false
        TelnyxCommon.getInstance().setHandlingPush(true)

        userSessionJob?.cancel()
        userSessionJob = null


        userSessionJob = viewModelScope.launch {
            RejectIncomingPushCall(context = viewContext)
                .invoke(txPushMetaData) {
                    _isLoading.value = false
                }
                .asFlow().collectLatest { response ->
                    Timber.d("Rejecting income push response: $response")
                    handleSocketResponse(response, true)
                }
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
        initCallHistory(context)
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
            loadCallHistoryForCurrentProfile()
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
        disconnectedByUser = false

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
                // Mark this to avoid misleading error message
                disconnectedByUser = true
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

    /**
     * Makes a pre-call diagnosis call to the provided test number.
     *
     * This method will make a call to testNumber and will collect the call quality metrics
     * at the end of the call.
     * Those metrics will be then added to the precallDiagnosisState flow.
     *
     * @param viewContext The application context.
     * @param testNumber The number to call for the precall diagnosis.
     */
    fun makePreCallDiagnosis(viewContext: Context, testNumber: String) {
        // Make a call to the texml_number
        callStateJob?.cancel()
        callStateJob = null

        callStateJob = viewModelScope.launch {
            ProfileManager.getLoggedProfile(viewContext)?.let { currentProfile ->
                Log.d("Call", "clicked profile ${currentProfile.sipUsername}")
                SendInvite(viewContext).invoke(
                    currentProfile.callerIdName ?: "",
                    currentProfile.callerIdNumber ?: "",
                    testNumber,
                    "",
                    mapOf(Pair("X-test", "123456")),
                    true
                ).callStateFlow.collect {
                    handlePrecallDiagnosisCallState(it)
                }
            }
        }

        collectPreCallDiagnosis()
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

        // Start collecting websocket messages
        collectWebsocketMessages()
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
     * Error dialog is shown only when the disconnect was not intentional (user-initiated).
     * @param response The error response to handle.
     */
    private fun handleError(response: SocketResponse<ReceivedMessageBody>) {
        if (disconnectedByUser && response.errorCode == SocketError.GATEWAY_FAILURE_ERROR.errorCode) {
            handleDisconnect()
            disconnectedByUser = false
            return
        }

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
        userSessionJob?.cancel()
        userSessionJob = null
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

    private fun handlePrecallDiagnosisCallState(callState: CallState) {
        when (callState) {
            is CallState.DONE -> {
                preparePreCallDiagnosis()?.let {
                    _precallDiagnosisState.value = TelnyxPrecallDiagnosisState.PrecallDiagnosisCompleted(it)
                } ?: run {
                    _precallDiagnosisState.value = TelnyxPrecallDiagnosisState.PrecallDiagnosisFailed
                }
            }
            is CallState.ERROR,
            is CallState.DROPPED -> {
                _precallDiagnosisState.value = TelnyxPrecallDiagnosisState.PrecallDiagnosisFailed
            }
            else -> {
                _precallDiagnosisState.value = TelnyxPrecallDiagnosisState.PrecallDiagnosisStarted
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
                    "",
                    mapOf(Pair("X-test", "123456")),
                    debug,
                    onCallHistoryAdd = { number ->
                        addCallToHistory(CallType.OUTBOUND, number)
                    }
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
                    debug,
                    onCallHistoryAdd = { number ->
                        addCallToHistory(CallType.INBOUND, number)
                    }
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

    /**
     * Initializes the call history repository and loads call history for the current profile.
     *
     * @param context The application context.
     */
    private fun initCallHistory(context: Context) {
        callHistoryRepository = CallHistoryRepository(context)
        loadCallHistoryForCurrentProfile()
    }

    /**
     * Loads call history for the current profile.
     */
    private fun loadCallHistoryForCurrentProfile() {
        val profile = _currentProfile.value
        if (profile != null && callHistoryRepository != null) {
            viewModelScope.launch {
                callHistoryRepository!!.getCallHistoryForProfile(profile.callerIdName ?: "Unknown").collect { entities ->
                    _callHistoryList.value = entities.map { entity ->
                        CallHistoryItem(
                            id = entity.id,
                            userProfileName = entity.userProfileName,
                            callType = if (entity.callType == "inbound") CallType.INBOUND else CallType.OUTBOUND,
                            destinationNumber = entity.destinationNumber,
                            date = entity.date
                        )
                    }
                }
            }
        }
    }

    /**
     * Adds a call to the call history.
     *
     * @param callType The type of call (inbound or outbound).
     * @param destinationNumber The destination number.
     */
    suspend fun addCallToHistory(callType: CallType, destinationNumber: String) {
        val profile = _currentProfile.value
        if (profile != null && callHistoryRepository != null) {
            val callTypeString = if (callType == CallType.INBOUND) "inbound" else "outbound"
            callHistoryRepository!!.addCall(profile.callerIdName ?: "Unknown", callTypeString, destinationNumber)
        }
    }

    /**
     * Deletes the call history for the current profile.
     */
    suspend fun deleteCallHistoryForProfile(profileName: String) {
        callHistoryRepository?.deleteCallHistoryForProfile(profileName)
    }

    /**
     * Starts collecting websocket messages from the TelnyxClient.
     * This should be called when the user is logged in.
     */
    fun collectWebsocketMessages() {
        // Cancel any previous collector job first to avoid multiple collectors
        wsMessagesCollectorJob?.cancel()
        wsMessagesCollectorJob = viewModelScope.launch {
            Timber.d("Websocket message collection started.")
            val telnyxClient = TelnyxCommon.getInstance().telnyxClient
            telnyxClient?.getWsMessageResponse()?.asFlow()?.collect { message ->
                if (message != null) {
                    val currentMessages = _wsMessages.value.toMutableList()
                    // Add new message at the beginning (latest first)
                    currentMessages.add(0, WebsocketMessage(message))
                    // Limit the number of messages to avoid memory issues
                    while (currentMessages.size > MAX_WS_MESSAGES) {
                        currentMessages.removeAt(currentMessages.size - 1)
                    }
                    _wsMessages.value = currentMessages
                }
            }
        }
    }

    /**
     * Clears the websocket messages list.
     */
    fun clearWebsocketMessages() {
        _wsMessages.value = emptyList()
    }

    private fun collectPreCallDiagnosis() {
        precallDiagnosisCollectorJob?.cancel()
        precallDiagnosisData = mutableListOf()
        precallDiagnosisCollectorJob = viewModelScope.launch {
            TelnyxCommon.getInstance().callQualityMetrics.collect { metrics ->
                metrics?.let {
                    if (it.quality != CallQuality.UNKNOWN)
                        precallDiagnosisData?.add(it)
                }
            }
        }
    }

    private fun preparePreCallDiagnosis(): PreCallDiagnosis? {
        return precallDiagnosisData?.let { preCallDiagnosisData ->
            try {
                val minJitter = preCallDiagnosisData.minOf { it.jitter }
                val maxJitter = preCallDiagnosisData.maxOf { it.jitter }
                val avgJitter = preCallDiagnosisData.sumOf { it.jitter } / preCallDiagnosisData.size

                val minRtt = preCallDiagnosisData.minOf { it.rtt }
                val maxRtt = preCallDiagnosisData.maxOf { it.rtt }
                val avgRtt = preCallDiagnosisData.sumOf { it.rtt } / preCallDiagnosisData.size

                val avgMos = preCallDiagnosisData.sumOf { it.mos } / preCallDiagnosisData.size

                val mostFrequentQuality = preCallDiagnosisData
                    .groupingBy { it.quality }
                    .eachCount()
                    .maxByOrNull { it.value }
                    ?.key

                val bytesSent = preCallDiagnosisData.lastOrNull()?.outboundAudio?.get("bytesSent")?.toString()?.toLongOrNull() ?: 0L
                val bytesReceived = preCallDiagnosisData.lastOrNull()?.inboundAudio?.get("bytesReceived")?.toString()?.toLongOrNull() ?: 0L
                val packetsSent = preCallDiagnosisData.lastOrNull()?.outboundAudio?.get("packetsSent")?.toString()?.toLongOrNull() ?: 0L
                val packetsReceived = preCallDiagnosisData.lastOrNull()?.inboundAudio?.get("packetsReceived")?.toString()?.toLongOrNull() ?: 0L

                val iceCandidatesList = preCallDiagnosisData
                    .flatMap { it.iceCandidates ?: emptyList() }
                    .distinctBy { it.id }

                PreCallDiagnosis(
                    mos = avgMos,
                    quality = mostFrequentQuality ?: CallQuality.UNKNOWN,
                    jitter = MetricSummary(minJitter, maxJitter, avgJitter),
                    rtt = MetricSummary(minRtt, maxRtt, avgRtt),
                    bytesSent = bytesSent,
                    bytesReceived = bytesReceived,
                    packetsSent = packetsSent,
                    packetsReceived = packetsReceived,
                    iceCandidates = iceCandidatesList
                )
            } catch (e: Throwable) {
                Timber.e("Pre-call diagnosis data processing error ${e.message}")
                null
            }

        }
    }

    override fun onCleared() {
        super.onCleared()
        audioLevelCollectorJob?.cancel()
        wsMessagesCollectorJob?.cancel()
        userSessionJob?.cancel()
        precallDiagnosisCollectorJob?.cancel()
    }

    companion object {
        private const val MAX_AUDIO_LEVELS = 100
        private const val MAX_WS_MESSAGES = 100
    }
}
