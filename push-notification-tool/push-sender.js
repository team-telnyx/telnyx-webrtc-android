const axios = require('axios');
const { GoogleAuth } = require('google-auth-library');

class PushSender {
    constructor() {
        this.v1BaseUrl = 'https://fcm.googleapis.com/v1/projects';
    }

    async sendPushNotification(config) {
        return await this.sendV1Notification(config);
    }

    async sendV1Notification(config) {
        try {
            let auth;
            if (config.serviceAccountJson) {
                auth = new GoogleAuth({
                    credentials: JSON.parse(config.serviceAccountJson),
                    scopes: ['https://www.googleapis.com/auth/firebase.messaging']
                });
            } else {
                auth = new GoogleAuth({
                    keyFile: config.serviceAccountPath,
                    scopes: ['https://www.googleapis.com/auth/firebase.messaging']
                });
            }

            const accessToken = await auth.getAccessToken();
            const projectId = config.projectId;

            const payload = {
                message: {
                    token: config.deviceToken,
                    data: {
                        type: 'voip',
                        message: 'Telnyx VoIP Push Notification Tester',
                        call_id: '87654321-dcba-4321-dcba-0987654321fe',
                        caller_name: config.data.caller_name,
                        caller_number: config.data.caller_number,
                        voice_sdk_id: '12345678-abcd-1234-abcd-1234567890ab',
                        metadata: JSON.stringify({
                            call_id: '87654321-dcba-4321-dcba-0987654321fe',
                            caller_name: config.data.caller_name,
                            caller_number: config.data.caller_number,
                            voice_sdk_id: '12345678-abcd-1234-abcd-1234567890ab'
                        })
                    },
                    android: {
                        priority: 'high'
                    }
                }
            };

            const url = `${this.v1BaseUrl}/${projectId}/messages:send`;
            const headers = {
                'Authorization': `Bearer ${accessToken}`,
                'Content-Type': 'application/json'
            };

            console.log('\n📤 Sending push notification...');
            console.log('URL:', url);
            console.log('Project ID:', projectId);
            console.log('Device Token (first 20 chars):', config.deviceToken.substring(0, 20) + '...');
            console.log('Payload:', JSON.stringify(payload, null, 2));

            const response = await axios.post(url, payload, { headers });

            if (response.status === 200) {
                console.log('\n✅ Push notification sent successfully!');
                console.log('Message Name:', response.data.name);
                return { success: true, data: response.data };
            } else {
                throw new Error(`HTTP ${response.status}: ${response.statusText}`);
            }
        } catch (error) {
            console.log('\n❌ Failed to send push notification:');
            if (error.response) {
                console.log('Status:', error.response.status);
                console.log('Response:', JSON.stringify(error.response.data, null, 2));
                return { success: false, error: error.response.data };
            } else {
                console.log('Error:', error.message);
                return { success: false, error: error.message };
            }
        }
    }

}

module.exports = PushSender;