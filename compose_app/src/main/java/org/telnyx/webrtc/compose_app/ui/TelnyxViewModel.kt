package org.telnyx.webrtc.compose_app.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.telnyx.webrtc.common.domain.authentication.AuthenticateBySIPCredentials
import com.telnyx.webrtc.common.domain.authentication.AuthenticateByToken
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.model.PushMetaData
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.SocketStatus
import com.telnyx.webrtc.sdk.verto.receive.AnswerResponse
import com.telnyx.webrtc.sdk.verto.receive.ByeResponse
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.LoginResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.RingingResponse
import com.telnyx.webrtc.sdk.verto.receive.SocketObserver
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.telnyx.webrtc.compose_app.R
import timber.log.Timber
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

    private val _credentialConfigList = MutableStateFlow<List<CredentialConfig>>(
        listOf(
            CredentialConfig(
                sipUser = "",
                sipPassword = "",
                sipCallerIDName = "",
                sipCallerIDNumber = "",
                fcmToken = "",
                ringtone = "",
                ringBackTone = R.raw.ringback_tone,
                logLevel = com.telnyx.webrtc.sdk.model.LogLevel.ALL
            )
        )
    )
    val credentialConfigList: StateFlow<List<CredentialConfig>> = _credentialConfigList

    private val _currentConfig = MutableStateFlow<CredentialConfig?>(null)
    val currentConfig: StateFlow<CredentialConfig?>  = _currentConfig

    fun setCurrentConfig(credentialConfig: CredentialConfig) {
        _currentConfig.value = credentialConfig
    }


    fun addCredentialConfig(credentialConfig: CredentialConfig) {
        val list = _credentialConfigList.value.toMutableList()
        list.add(credentialConfig)
        _credentialConfigList.value = list
    }


    fun credentialLogin(
        viewContext: Context,
        credentialConfig: CredentialConfig,
        txPushMetaData: String?,
        autoLogin: Boolean = true
    ) {
        viewModelScope.launch {
            AuthenticateBySIPCredentials(context = viewContext).invoke(
                credentialConfig,
                txPushMetaData,
                autoLogin
            ).asFlow().collectLatest { response ->
                handleSocketResponse(response)
            }
        }
    }

    fun getFCMToken() {
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