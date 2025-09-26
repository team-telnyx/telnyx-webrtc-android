# Real-time Transcript Updates

## Overview

During AI Assistant conversations, the SDK provides real-time transcript updates that include both the caller's speech and the AI Assistant's responses. This allows you to display a live conversation transcript in your application.

## Transcript Properties

The SDK provides two main ways to access transcript data:

### SharedFlow for Real-time Updates

```kotlin
val transcriptUpdateFlow: SharedFlow<List<TranscriptItem>>
```

### Current Transcript Access

```kotlin
val transcript: List<TranscriptItem>
```

## TranscriptItem Structure

```kotlin
data class TranscriptItem(
    val id: String,                    // Unique identifier
    val role: String,                  // "user" or "assistant"
    val content: String,               // The transcribed text
    val timestamp: Date,               // When the item was created
    val isPartial: Boolean = false     // Whether this is a partial response
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
```

## Setting Up Transcript Updates

### Using SharedFlow (Recommended)

```kotlin
class AIConversationActivity : AppCompatActivity() {
    
    private val conversationTranscript = mutableListOf<TranscriptItem>()
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Set up transcript listener
        setupTranscriptListener()
    }
    
    private fun setupTranscriptListener() {
        lifecycleScope.launch {
            telnyxClient.transcriptUpdateFlow.collect { transcript ->
                // Update UI with new transcript
                updateConversationUI(transcript)
            }
        }
    }
    
    private fun updateConversationUI(transcript: List<TranscriptItem>) {
        runOnUiThread {
            conversationTranscript.clear()
            conversationTranscript.addAll(transcript)
            
            // Update RecyclerView or other UI components
            conversationAdapter.notifyDataSetChanged()
            
            // Auto-scroll to bottom
            recyclerView.scrollToPosition(conversationTranscript.size - 1)
        }
    }
}
```

### Processing Individual Transcript Items

```kotlin
private fun updateConversationUI(transcript: List<TranscriptItem>) {
    transcript.forEach { item ->
        when (item.role) {
            TranscriptItem.ROLE_USER -> {
                Log.d("Transcript", "User said: ${item.content}")
                // Display user message in UI
                addUserMessage(item.content, item.timestamp)
            }
            TranscriptItem.ROLE_ASSISTANT -> {
                Log.d("Transcript", "Assistant said: ${item.content}")
                // Display assistant message in UI
                addAssistantMessage(item.content, item.timestamp, item.isPartial)
            }
        }
    }
}
```

## Manual Transcript Access

You can also manually retrieve the current transcript at any time:

```kotlin
// Get current transcript
val currentTranscript = telnyxClient.transcript

// Process the transcript
currentTranscript.forEach { item ->
    println("${item.role}: ${item.content} (${item.timestamp})")
}
```

## Handling Partial Responses

AI Assistant responses may come in chunks (partial responses). Handle these appropriately:

```kotlin
private fun addAssistantMessage(content: String, timestamp: Date, isPartial: Boolean) {
    if (isPartial) {
        // Update existing message or show typing indicator
        updateLastAssistantMessage(content)
        showTypingIndicator(true)
    } else {
        // Final message - hide typing indicator
        showTypingIndicator(false)
        finalizeAssistantMessage(content, timestamp)
    }
}
```

## Complete Example with RecyclerView

```kotlin
class ConversationAdapter(
    private val transcript: List<TranscriptItem>
) : RecyclerView.Adapter<ConversationAdapter.ViewHolder>() {
    
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timestamp: TextView = view.findViewById(R.id.timestamp)
        val roleIndicator: View = view.findViewById(R.id.roleIndicator)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.conversation_item, parent, false)
        return ViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = transcript[position]
        
        holder.messageText.text = item.content
        holder.timestamp.text = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            .format(item.timestamp)
        
        // Style based on role
        when (item.role) {
            TranscriptItem.ROLE_USER -> {
                holder.roleIndicator.setBackgroundColor(Color.BLUE)
                holder.messageText.gravity = Gravity.END
            }
            TranscriptItem.ROLE_ASSISTANT -> {
                holder.roleIndicator.setBackgroundColor(Color.GREEN)
                holder.messageText.gravity = Gravity.START
                
                // Show partial indicator
                if (item.isPartial) {
                    holder.messageText.alpha = 0.7f
                } else {
                    holder.messageText.alpha = 1.0f
                }
            }
        }
    }
    
    override fun getItemCount() = transcript.size
}
```

## Widget Settings Access

Access AI conversation widget settings:

```kotlin
// Get current widget settings
val widgetSettings = telnyxClient.currentWidgetSettings

widgetSettings?.let { settings ->
    // Use widget settings to configure UI
    Log.d("Widget", "Settings: $settings")
}
```

## Important Notes

- **AI Assistant Only**: Transcript updates are only available during AI Assistant conversations initiated through `anonymousLogin`
- **Real-time Updates**: Transcripts update in real-time as the conversation progresses
- **Partial Responses**: Assistant responses may come in chunks - handle `isPartial` flag appropriately
- **Memory Management**: Transcripts are cleared when calls end or when disconnecting
- **Thread Safety**: Always update UI on the main thread when processing transcript updates

## Error Handling

```kotlin
lifecycleScope.launch {
    try {
        telnyxClient.transcriptUpdateFlow.collect { transcript ->
            updateConversationUI(transcript)
        }
    } catch (e: Exception) {
        Log.e("Transcript", "Error processing transcript updates: ${e.message}")
    }
}
```

## Next Steps

After setting up transcript updates:
1. [Send text messages](https://developers.telnyx.com/development/webrtc/android-sdk/ai-agent/text-messaging) to interact with the AI Assistant via text