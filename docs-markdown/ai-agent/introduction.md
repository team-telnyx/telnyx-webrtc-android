# AI Agent Usage

The Android WebRTC SDK supports [Voice AI Agent](https://telnyx.com/products/voice-ai-agents) implementations. 

To get started, follow the steps [described here](https://telnyx.com/resources/ai-assistant-builder) to build your first AI Assistant. 

Once your AI Agent is up and running, you can use the SDK to communicate with your AI Agent with the following steps:

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