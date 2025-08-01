/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.model.*
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.verto.receive.AiConversationResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketResponse
import com.telnyx.webrtc.sdk.verto.send.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.*

/**
 * AIAssistantManager handles all AI assistant related functionality including
 * anonymous login, conversation management, transcript handling, and widget settings.
 * 
 * This class provides separation of concerns by isolating AI features from the main TelnyxClient.
 */
class AIAssistantManager(
    private val socket: TxSocket,
    private val sessid: String?
) {

    /**
     * Interface for AI Assistant event callbacks
     */
    interface AIAssistantDelegate {
        /**
         * Called when a socket response is received for AI operations
         */
        fun onSocketResponse(response: SocketResponse)
        
        /**
         * Called when transcript is updated
         */
        fun onTranscriptUpdated(transcript: List<TranscriptItem>)
        
        /**
         * Called when widget settings are updated
         */
        fun onWidgetSettingsUpdated(settings: WidgetSettings)
    }

    // Delegate for AI assistant events
    var delegate: AIAssistantDelegate? = null

    // Connection state tracking
    private var _targetId: String? = null
    private var _targetType: String = "ai_assistant"
    private var _targetVersionId: String? = null
    private var _isConnected: Boolean = false

    // Transcript management for AI conversations
    private val _transcript = mutableListOf<TranscriptItem>()
    private val assistantResponseBuffers = mutableMapOf<String, StringBuilder>()

    // Current widget settings from AI conversation
    private var _currentWidgetSettings: WidgetSettings? = null

    // SharedFlow for transcript updates
    private val _transcriptUpdateFlow = MutableSharedFlow<List<TranscriptItem>>(
        replay = 1,
        extraBufferCapacity = 64
    )

    /**
     * Returns the transcript updates in the form of SharedFlow
     * Contains a list of TranscriptItem objects representing the conversation
     */
    val transcriptUpdateFlow: SharedFlow<List<TranscriptItem>> =
        _transcriptUpdateFlow.asSharedFlow()

    /**
     * Returns the current transcript as an immutable list
     */
    val transcript: List<TranscriptItem>
        get() = _transcript.toList()

    /**
     * Returns the current widget settings from AI conversation
     */
    val currentWidgetSettings: WidgetSettings?
        get() = _currentWidgetSettings

    /**
     * Returns the current target ID for the AI assistant connection
     */
    val targetId: String?
        get() = _targetId

    /**
     * Returns the current target type for the AI assistant connection
     */
    val targetType: String
        get() = _targetType

    /**
     * Returns the current target version ID for the AI assistant connection
     */
    val targetVersionId: String?
        get() = _targetVersionId

    /**
     * Returns whether the AI assistant is currently connected
     */
    val isConnected: Boolean
        get() = _isConnected

    /**
     * Performs anonymous login to connect to an AI assistant
     *
     * @param targetId the unique identifier of the target assistant
     * @param targetType the type of target (defaults to "ai_assistant")
     * @param targetVersionId optional version ID of the target
     * @param userVariables optional user variables to include
     * @param reconnection whether this is a reconnection attempt
     */
    fun anonymousLogin(
        targetId: String,
        targetType: String = "ai_assistant",
        targetVersionId: String? = null,
        userVariables: Map<String, Any>? = null,
        reconnection: Boolean = false,
    ) {
        // Store connection parameters
        _targetId = targetId
        _targetType = targetType
        _targetVersionId = targetVersionId

        val uuid: String = UUID.randomUUID().toString()

        val userAgent = UserAgent(
            sdkVersion = BuildConfig.SDK_VERSION,
            data = "Android-${BuildConfig.SDK_VERSION}"
        )

        val anonymousLoginParams = AnonymousLoginParams(
            targetType = targetType,
            targetId = targetId,
            targetVersionId = targetVersionId,
            userVariables = userVariables,
            reconnection = reconnection,
            userAgent = userAgent,
            sessid = sessid
        )

        val loginMessage = SendingMessageBody(
            id = uuid,
            method = SocketMethod.ANONYMOUS_LOGIN.methodName,
            params = anonymousLoginParams
        )

        Logger.d(message = "AI Assistant Anonymous Login Message: ${Gson().toJson(loginMessage)}")
        socket.send(loginMessage)
    }

    /**
     * Handles AI conversation messages received from the socket
     * Processes transcript updates and widget settings
     *
     * @param jsonObject the socket response containing AI conversation data
     */
    fun handleAiConversationReceived(jsonObject: JsonObject) {
        Logger.i(message = "AI CONVERSATION RECEIVED :: $jsonObject")

        try {
            val aiConversationResponse =
                Gson().fromJson(jsonObject, AiConversationResponse::class.java)
            val params = aiConversationResponse.aiConversationParams

            // Store widget settings if available
            params?.widgetSettings?.let { settings ->
                _currentWidgetSettings = settings
                delegate?.onWidgetSettingsUpdated(settings)
                Logger.i(message = "Widget settings updated :: $_currentWidgetSettings")
            }

            // Process message for transcript extraction
            processAiConversationForTranscript(params)

            // Emit socket response through delegate
            val receivedMessageBody = ReceivedMessageBody(
                method = SocketMethod.AI_CONVERSATION.methodName,
                result = aiConversationResponse
            )
            delegate?.onSocketResponse(SocketResponse.aiConversation(receivedMessageBody))

        } catch (e: Exception) {
            Logger.e(message = "Error processing AI conversation message: ${e.message}")
        }
    }

    /**
     * Handles login response for anonymous login
     */
    fun handleLoginResponse() {
        _isConnected = true
        Logger.i(message = "AI Assistant connected successfully")
    }

    /**
     * Disconnects from the AI assistant and clears state
     */
    fun disconnect() {
        _isConnected = false
        _targetId = null
        _targetVersionId = null
        _currentWidgetSettings = null
        _transcript.clear()
        assistantResponseBuffers.clear()
        _transcriptUpdateFlow.tryEmit(emptyList())
        Logger.i(message = "AI Assistant disconnected")
    }

    /**
     * Clears the current transcript
     */
    fun clearTranscript() {
        _transcript.clear()
        assistantResponseBuffers.clear()
        _transcriptUpdateFlow.tryEmit(emptyList())
        delegate?.onTranscriptUpdated(emptyList())
        Logger.i(message = "AI Assistant transcript cleared")
    }

    /**
     * Process AI conversation messages for transcript extraction
     */
    private fun processAiConversationForTranscript(params: AiConversationParams?) {
        if (params?.type == null) return

        when (params.type) {
            "conversation.item.created" -> handleConversationItemCreated(params)
            "response.text.delta" -> handleResponseTextDelta(params)
            // Other AI conversation message types are ignored for transcript
        }
    }

    /**
     * Handle user speech transcript from conversation.item.created messages
     */
    private fun handleConversationItemCreated(params: AiConversationParams) {
        val item = params.item
        if (item?.role != TranscriptItem.ROLE_USER || item.status != "completed") {
            return // Only handle completed user messages
        }

        val content = item.content
            ?.mapNotNull { it.transcript }
            ?.joinToString(" ") ?: ""

        if (content.isNotEmpty() && item.id != null) {
            val transcriptItem = TranscriptItem(
                id = item.id,
                role = TranscriptItem.ROLE_USER,
                content = content,
                timestamp = Date()
            )

            _transcript.add(transcriptItem)
            val currentTranscript = _transcript.toList()
            _transcriptUpdateFlow.tryEmit(currentTranscript)
            delegate?.onTranscriptUpdated(currentTranscript)
        }
    }

    /**
     * Handle AI response text deltas from response.text.delta messages
     */
    private fun handleResponseTextDelta(params: AiConversationParams) {
        val delta = params.delta ?: return
        val itemId = params.itemId ?: return

        // Buffer the response text for this item ID
        if (!assistantResponseBuffers.containsKey(itemId)) {
            assistantResponseBuffers[itemId] = StringBuilder()
        }
        assistantResponseBuffers[itemId]?.append(delta)

        // Create or update transcript item for this response
        val existingIndex = _transcript.indexOfFirst { it.id == itemId }
        val currentContent = assistantResponseBuffers[itemId]?.toString() ?: ""

        if (existingIndex >= 0) {
            // Update existing transcript item with accumulated content
            _transcript[existingIndex] = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = _transcript[existingIndex].timestamp,
                isPartial = true
            )
        } else {
            // Create new transcript item
            val transcriptItem = TranscriptItem(
                id = itemId,
                role = TranscriptItem.ROLE_ASSISTANT,
                content = currentContent,
                timestamp = Date(),
                isPartial = true
            )
            _transcript.add(transcriptItem)
        }

        val currentTranscript = _transcript.toList()
        _transcriptUpdateFlow.tryEmit(currentTranscript)
        delegate?.onTranscriptUpdated(currentTranscript)
    }

    companion object {
        /**
         * Default target type for AI assistant connections
         */
        const val DEFAULT_TARGET_TYPE = "ai_assistant"
    }
}
