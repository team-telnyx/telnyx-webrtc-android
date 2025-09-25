# Sending Text Messages to AI Agents

## Overview

In addition to voice conversation, you can send text messages directly to the AI Agent during an active call. This allows for mixed-mode communication where users can both speak and type messages to the AI Assistant.

## Method Signature

```kotlin
fun sendAIAssistantMessage(message: String)
```

## Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `message` | String | The text message to send to the AI Assistant |

## Basic Usage

```kotlin
// Send a text message to the AI Agent during an active call
telnyxClient.sendAIAssistantMessage("Hello, can you help me with my account?")
```

## Complete Example

```kotlin
class AIConversationActivity : AppCompatActivity() {
    
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ai_conversation)
        
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        
        setupMessageSending()
    }
    
    private fun setupMessageSending() {
        sendButton.setOnClickListener {
            val message = messageInput.text.toString().trim()
            if (message.isNotEmpty()) {
                sendTextMessage(message)
                messageInput.text.clear()
            }
        }
        
        // Send on Enter key
        messageInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendButton.performClick()
                true
            } else {
                false
            }
        }
    }
    
    private fun sendTextMessage(message: String) {
        try {
            telnyxClient.sendAIAssistantMessage(message)
            Log.d("AI", "Text message sent: $message")
        } catch (e: Exception) {
            Log.e("AI", "Failed to send text message: ${e.message}")
            showError("Failed to send message")
        }
    }
}
```

## Advanced Usage with Call State Checking

```kotlin
class AIMessageManager(private val telnyxClient: TelnyxClient) {
    
    fun sendMessage(message: String): Boolean {
        return if (isAICallActive()) {
            telnyxClient.sendAIAssistantMessage(message)
            true
        } else {
            Log.w("AI", "Cannot send message: No active AI call")
            false
        }
    }
    
    private fun isAICallActive(): Boolean {
        return telnyxClient.calls.values.any { call ->
            call.callState == CallState.ACTIVE
        }
    }
    
    fun sendMessageWithConfirmation(message: String, callback: (Boolean) -> Unit) {
        if (sendMessage(message)) {
            // Listen for confirmation in transcript updates
            listenForMessageConfirmation(message, callback)
        } else {
            callback(false)
        }
    }
    
    private fun listenForMessageConfirmation(
        sentMessage: String, 
        callback: (Boolean) -> Unit
    ) {
        // Monitor transcript for the sent message appearing
        lifecycleScope.launch {
            telnyxClient.transcriptUpdateFlow.collect { transcript ->
                val userMessages = transcript.filter { 
                    it.role == TranscriptItem.ROLE_USER 
                }
                
                if (userMessages.any { it.content.contains(sentMessage) }) {
                    callback(true)
                }
            }
        }
    }
}
```

## UI Integration Example

```kotlin
class ConversationFragment : Fragment() {
    
    private lateinit var binding: FragmentConversationBinding
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentConversationBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupMessageInput()
        setupTranscriptDisplay()
    }
    
    private fun setupMessageInput() {
        binding.messageInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                binding.sendButton.isEnabled = !s.isNullOrBlank()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        
        binding.sendButton.setOnClickListener {
            sendCurrentMessage()
        }
    }
    
    private fun sendCurrentMessage() {
        val message = binding.messageInput.text.toString().trim()
        if (message.isNotEmpty()) {
            // Show message as "sending" in UI
            addPendingMessage(message)
            
            // Send to AI Assistant
            telnyxClient.sendAIAssistantMessage(message)
            
            // Clear input
            binding.messageInput.text.clear()
        }
    }
    
    private fun addPendingMessage(message: String) {
        // Add message to UI with "sending" indicator
        // This will be replaced when transcript updates arrive
    }
}
```

## Message Types and Formatting

```kotlin
class AIMessageHelper {
    
    fun sendQuestion(question: String) {
        telnyxClient.sendAIAssistantMessage("Question: $question")
    }
    
    fun sendCommand(command: String) {
        telnyxClient.sendAIAssistantMessage("Command: $command")
    }
    
    fun sendContextualMessage(message: String, context: Map<String, String>) {
        val contextString = context.entries.joinToString(", ") { "${it.key}: ${it.value}" }
        telnyxClient.sendAIAssistantMessage("$message [Context: $contextString]")
    }
    
    fun sendFormattedMessage(message: String, format: MessageFormat) {
        val formattedMessage = when (format) {
            MessageFormat.URGENT -> "URGENT: $message"
            MessageFormat.QUESTION -> "❓ $message"
            MessageFormat.FEEDBACK -> "💬 $message"
            MessageFormat.PLAIN -> message
        }
        telnyxClient.sendAIAssistantMessage(formattedMessage)
    }
}

enum class MessageFormat {
    URGENT, QUESTION, FEEDBACK, PLAIN
}
```

## Error Handling

```kotlin
private fun sendTextMessageWithErrorHandling(message: String) {
    try {
        // Check if we have an active AI call
        val hasActiveCall = telnyxClient.calls.values.any { 
            it.callState == CallState.ACTIVE 
        }
        
        if (!hasActiveCall) {
            showError("No active AI conversation. Please start a call first.")
            return
        }
        
        // Send the message
        telnyxClient.sendAIAssistantMessage(message)
        
        // Show success feedback
        showMessageSent(message)
        
    } catch (e: Exception) {
        Log.e("AI", "Failed to send message: ${e.message}")
        showError("Failed to send message: ${e.message}")
    }
}

private fun showError(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

private fun showMessageSent(message: String) {
    // Optional: Show brief confirmation
    Log.d("AI", "Message sent successfully: $message")
}
```

## Important Notes

- **Active Call Required**: You must have an active call established before sending text messages
- **AI Assistant Only**: The `sendAIAssistantMessage` method is only available during AI Assistant conversations
- **Transcript Integration**: Text messages sent this way will appear in transcript updates alongside spoken conversation
- **Processing**: The AI Agent will process and respond to text messages just like spoken input
- **Mixed Communication**: Users can seamlessly switch between voice and text communication

## Best Practices

1. **Validate Input**: Always check that messages are not empty before sending
2. **Check Call State**: Verify an active AI call exists before sending messages
3. **User Feedback**: Provide visual feedback when messages are sent
4. **Error Handling**: Handle network errors and call state issues gracefully
5. **UI Updates**: Update the conversation UI immediately for better user experience

## Integration with Transcript Updates

Text messages will appear in the transcript flow:

```kotlin
lifecycleScope.launch {
    telnyxClient.transcriptUpdateFlow.collect { transcript ->
        transcript.forEach { item ->
            when (item.role) {
                TranscriptItem.ROLE_USER -> {
                    // This includes both spoken words and text messages
                    displayUserMessage(item.content, item.timestamp)
                }
                TranscriptItem.ROLE_ASSISTANT -> {
                    // AI responses to both voice and text
                    displayAssistantMessage(item.content, item.timestamp)
                }
            }
        }
    }
}
```

This feature enables rich conversational experiences where users can seamlessly switch between voice and text communication with the AI Assistant.