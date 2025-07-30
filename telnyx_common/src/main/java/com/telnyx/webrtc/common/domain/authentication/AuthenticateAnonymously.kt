package com.telnyx.webrtc.common.domain.authentication

import android.content.Context
import com.telnyx.webrtc.common.TelnyxCommon
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import kotlinx.coroutines.flow.SharedFlow

/**
 * This class handles the anonymous authentication process for AI assistant connections.
 *
 * @param context The context used to access application-specific resources.
 */
class AuthenticateAnonymously(private val context: Context) {

    /**
     * Authenticates anonymously and returns a SharedFlow
     *
     * @param serverConfig The configuration for the server.
     * @param targetId The unique identifier of the target AI assistant.
     * @param targetType The type of target (defaults to "ai_assistant").
     * @param targetVersionId Optional version ID of the target.
     * @param userVariables Optional user variables to include.
     * @param reconnection Whether this is a reconnection attempt (defaults to false).
     * @param logLevel The log level for the operation (defaults to LogLevel.NONE).
     *
     * @return A SharedFlow emitting the socket response.
     */
    fun invokeFlow(
        serverConfig: TxServerConfiguration = TxServerConfiguration(),
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
        logLevel: LogLevel = LogLevel.NONE
    ): SharedFlow<SocketResponse<ReceivedMessageBody>> {
        val telnyxClient = TelnyxCommon.getInstance().getTelnyxClient(context)

        // Use the new connectAnonymously method that handles connection and login properly
        telnyxClient.connectAnonymously(
            providedServerConfig = serverConfig,
            targetId = targetId,
            targetType = targetType,
            targetVersionId = targetVersionId,
            userVariables = userVariables,
            reconnection = reconnection,
            logLevel = logLevel
        )

        return telnyxClient.socketResponseFlow
    }
}
