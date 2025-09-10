# Telnyx VoIP Push Notification Tester

A Node.js command-line tool for testing VoIP push notifications with Firebase Cloud Messaging HTTP v1 API for the Telnyx WebRTC Android SDK.

## Features

- 🔔 Send VoIP push notifications via Firebase FCM v1 API
- 💾 Save and reuse previous configurations
- ✏️  Update specific configuration values
- ✅ Configuration validation
- 🎨 Interactive CLI with colored output
- 🔐 Service account authentication (JSON or file path)
- 📱 Optimized for Android VoIP integration

## Installation

1. Navigate to the tool directory:
```bash
cd push-notification-tool
```

2. Install dependencies:
```bash
npm install
```

## Usage

Run the tool:
```bash
npm start
# or
node index.js
```

### First Time Setup

When running for the first time, you'll be prompted to create a new configuration with:

- **Device FCM Token**: The target device's FCM registration token
- **Firebase Project ID**: Your Firebase project ID (default: "telnyx-webrtc-notifications")
- **Service Account**: Firebase service account credentials (JSON content or file path)
- **Caller Name**: Display name of the caller (default: "John Doe")
- **Caller Number**: Caller's phone number (default: "+123456789")

### Using with Different Firebase Projects

This tool can be used with any Firebase project that has FCM enabled. To use it with a different project:

1. **Set the correct Project ID**: Enter your Firebase project ID when prompted
2. **Provide matching Service Account**: Use a service account JSON from the same Firebase project
3. **Ensure FCM is enabled**: Verify that Firebase Cloud Messaging is enabled in your project

### Static Configuration Values

The following values are automatically set and cannot be changed:

- **Call ID**: `87654321-dcba-4321-dcba-0987654321fe`
- **Voice SDK ID**: `12345678-abcd-1234-abcd-1234567890ab`
- **Type**: `voip`
- **Priority**: `high`
- **Message**: `Telnyx VoIP Push Notification Tester`

### Subsequent Runs

If you have a saved configuration, you can:

- ✅ **Use previous configuration**: Send with existing settings
- ✏️  **Update some values**: Modify specific fields only
- 🔄 **Start fresh with new configuration**: Create entirely new settings

## Configuration File

The tool automatically saves your configuration to `last-config.json` in the same directory. This file contains:

```json
{
  "deviceToken": "target-device-fcm-token",
  "projectId": "your-firebase-project-id",
  "serviceAccountPath": "path/to/service-account.json",
  "serviceAccountJson": "",
  "data": {
    "caller_name": "John Doe",
    "caller_number": "+123456789"
  }
}
```

## Firebase Setup

To use this tool, you need:

1. A Firebase project with FCM enabled
2. **Service Account JSON** from Firebase Console:
   - Go to Project Settings → Service Accounts
   - Click "Generate new private key"
   - Download the JSON file or copy its content
3. Target device's FCM registration token

### Getting Service Account Credentials

1. **Firebase Console** → Select your project
2. **Project Settings** → **Service Accounts** tab
3. Click **"Generate new private key"**
4. Either:
   - Download the JSON file and provide the file path
   - Copy the JSON content and paste it directly

## Push Notification Format

The tool sends notifications using FCM HTTP v1 API in this format:

```json
{
  "message": {
    "token": "DEVICE_FCM_TOKEN",
    "data": {
      "type": "voip",
      "message": "Telnyx VoIP Push Notification Tester",
      "call_id": "87654321-dcba-4321-dcba-0987654321fe",
      "caller_name": "John Doe",
      "caller_number": "+123456789",
      "voice_sdk_id": "12345678-abcd-1234-abcd-1234567890ab",
      "metadata": "{\"call_id\":\"87654321-dcba-4321-dcba-0987654321fe\",\"caller_name\":\"John Doe\",\"caller_number\":\"+123456789\",\"voice_sdk_id\":\"12345678-abcd-1234-abcd-1234567890ab\"}"
    },
    "android": {
      "priority": "high"
    }
  }
}
```

## Requirements

- Node.js (version 12 or higher)
- Valid Firebase project with FCM v1 API enabled
- Firebase service account with messaging permissions
- Target device with FCM token

## Dependencies

- `inquirer`: Interactive command line prompts
- `axios`: HTTP client for FCM requests
- `chalk`: Terminal colors and styling
- `google-auth-library`: Google OAuth2 authentication

## Authentication

This tool uses the modern FCM HTTP v1 API with OAuth2 authentication via Google service accounts, providing better security than the legacy server key approach.