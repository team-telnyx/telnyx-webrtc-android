# Push Notification Troubleshooting

This guide helps you troubleshoot common issues with push notifications in the Telnyx WebRTC Android SDK.

## Common Points of Failure

### 1. FCM Token Not Passed to Login

One of the most common issues is that the FCM token is not being passed correctly during the login process.

**How to verify:**
- Check your application logs to ensure the FCM token is being retrieved successfully
- Verify that the login message contains the FCM token
- Look for logs similar to: `FCM token received: [your-token]`

**Solution:**
- Make sure you're retrieving the FCM token as shown in the [App Setup](./app-setup.md) guide
- Ensure the token is passed to the `connect()` method with the `txPushMetaData` parameter
- Verify that the token is not null or empty before passing it

### 2. Incorrect google-services.json Configuration

If your Firebase configuration file is incorrect or outdated, push notifications will not work properly.

**How to verify:**
- Check that the google-services.json file is in the correct location (app module root directory)
- Verify that the package name in the google-services.json matches your application's package name

**Solution:**
- Download a fresh copy of the google-services.json file from the Firebase Console
- Make sure you're using the correct Firebase project for your application
- Follow the [Portal Setup](./portal-setup.md) guide to properly configure Firebase

### 3. Wrong Push Credential Assigned to SIP Credential

If the push credential is not correctly assigned to your SIP credential, the server won't know where to send push notifications.

**How to verify:**
- Log into the Telnyx Portal and check your SIP Connection settings
- Verify that the correct Android push credential is selected in the WebRTC tab

**Solution:**
- Follow the steps in the [Portal Setup](./portal-setup.md) guide to properly assign the push credential
- Make sure you've selected the correct credential for your application

### 4. Incorrect Push Credential

If your push credential contains incorrect information, push notifications will fail.

**How to verify:**
- Check the push credential in the Telnyx Portal
- Verify that the server key JSON is correctly formatted and valid

**Solution:**
- Generate a new server key in the Firebase Console
- Update your push credential in the Telnyx Portal with the new key
- Test push notifications after updating the credential

### 5. Login Method Limitations

The way you authenticate with the Telnyx WebRTC SDK can affect push notification functionality.

**How to verify:**
- Check which login method you're using: SIP Connection, Generated Credential, or Token
- Refer to the [Dialing Registered Clients documentation](https://developers.telnyx.com/docs/voice/webrtc/sdk-commonalities#dialing-registered-clients)

**Solution:**
- Be aware that there are limitations on which registered clients can be called
- Test direct socket-connected calls before attempting push notification calls
- If using a token-based authentication, ensure the token has the necessary permissions

## Additional Troubleshooting Steps

1. **Check Firebase Console Logs**
   - Go to the Firebase Console > Your Project > Engage > Messaging
   - Check for any errors or failed deliveries

2. **Verify Android Manifest Configuration**
   - Ensure your AndroidManifest.xml has the correct permissions and service declarations
   - Check that your FirebaseMessagingService is properly registered

3. **Test with Firebase Test Notifications**
   - Use the Firebase Console to send a test notification to your device
   - This can help determine if the issue is with Firebase or with Telnyx

4. **Check Network Connectivity**
   - Ensure your device has a stable internet connection
   - Firebase Cloud Messaging requires network connectivity to receive notifications

5. **Verify Device Registration**
   - Make sure your device is properly registered with Firebase
   - Check that the FCM token is being refreshed when needed

## Still Having Issues?

If you've gone through all the troubleshooting steps and are still experiencing problems:

1. Check the Telnyx WebRTC SDK documentation for any updates or known issues
2. Contact Telnyx Support with detailed information about your setup and the issues you're experiencing
3. Include logs, error messages, and steps to reproduce the problem when contacting support