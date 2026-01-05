# AI Agent Usage

The Android WebRTC SDK supports [Voice AI Agent](https://telnyx.com/products/voice-ai-agents) implementations. 

To get started, follow the steps [described here](https://telnyx.com/resources/ai-assistant-builder) to build your first AI Assistant. 

Once your AI Agent is up and running, you can use the SDK to communicate with your AI Agent with the following steps:

## Pre-developed AI Widget

If you don't want to develop your own custom AI Agent interface from scratch, you can utilize our pre-developed AI Agent widget that provides a drop-in solution for voice AI interactions.

### Android Telnyx Voice AI Widget

The **Android Telnyx Voice AI Widget** is a standalone, embeddable widget that provides a complete voice AI assistant interface using the Telnyx WebRTC SDK.

**Repository**: [https://github.com/team-telnyx/android-telnyx-voice-ai-widget](https://github.com/team-telnyx/android-telnyx-voice-ai-widget)

**Maven Central**: `com.telnyx:android-voice-ai-widget:1.0.0`

### Key Features

- **Drop-in Solution**: Easy integration with minimal setup
- **Multiple UI States**: Collapsed, loading, expanded, and transcript views
- **Icon-Only Mode**: Floating action button-style interface for minimal UI footprint
- **Audio Visualizer**: Real-time audio visualization during conversations
- **Theme Support**: Light and dark theme compatibility
- **Responsive Design**: Optimized for various screen sizes
- **Voice Controls**: Mute/unmute and call management
- **Transcript View**: Full conversation history with text input
- **Customizable Modifiers**: Fine-tuned UI customization options

### Quick Integration

```kotlin
import com.telnyx.voiceai.widget.AIAssistantWidget

@Composable
fun MyScreen() {
    var showWidget by remember { mutableStateOf(false) }
    
    AIAssistantWidget(
        assistantId = "your-assistant-id",
        shouldInitialize = showWidget,
        modifier = Modifier.fillMaxWidth()
    )
}
```

### Icon-Only Mode

```kotlin
AIAssistantWidget(
    assistantId = "your-assistant-id",
    shouldInitialize = true,
    iconOnly = true, // Enables floating action button mode
    modifier = Modifier.fillMaxWidth()
)
```

This widget handles all the complexity of AI Agent integration, providing a production-ready solution that you can customize to match your app's design.

## Documentation Structure

This directory contains detailed documentation for AI Agent integration:

- [Anonymous Login](anonymous-login.md) - How to connect to AI assistants without traditional authentication
- [Starting Conversations](starting-conversations.md) - How to initiate calls with AI assistants
- [Transcript Updates](transcript-updates.md) - Real-time conversation transcripts
- [Text Messaging](text-messaging.md) - Send text messages during active calls

## Quick Start

1. **Anonymous Login**: Use `anonymousLogin()` to connect to your AI assistant
2. **Start Conversation**: Use `newInvite()` to initiate a call (destination is ignored)
3. **Receive Transcripts**: Listen to `transcriptUpdateFlow` for real-time conversation updates
4. **Send Text Messages**: Use `sendAIAssistantMessage()` to send text during active calls

## Key Features

- **No Authentication Required**: Connect to AI assistants without SIP credentials
- **Real-time Transcripts**: Get live conversation updates with role identification
- **Mixed Communication**: Combine voice and text messaging in the same conversation
- **Widget Settings**: Access AI conversation configuration settings
- **Standard Call Controls**: Use existing call management methods (mute, hold, end call)

## Important Notes

- After `anonymousLogin()`, all subsequent calls are routed to the specified AI assistant
- Transcript functionality is only available for AI assistant conversations
- AI assistants automatically answer calls - no manual answer required
- Text messages appear in transcript updates alongside spoken conversation