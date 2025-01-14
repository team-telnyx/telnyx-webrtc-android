The Telnyx Android Client WebRTC SDK makes use of Firebase Cloud Messaging to deliver push notifications. If you want to receive notifications when receiving calls on your Android mobile device you need to enable Firebase Cloud Messaging within your application.

To do this you need to:

- Set up a Firebase console account
- Create a Firebase project
- Add Firebase to your Android Application
- Setup a Push Credential within the Telnyx Portal
- Generate a Firebase Cloud Messaging instance token
- Send the token with your login message

Adding Firebase to your application is a simple process. Click on the Android icon on the home screen of the console to start:
![firebase-pn-screen](/img/firebase.png)

Next, enter your application details and register your application
![add-firebase-to-app](/img/add-firebase-to-app.png)

After your application is registered, Firebase will generate a google-services.json file for you which will need to be added to your project root directory:

![download-config-file-firebase](/img/download-config-file-firebase.png)

After that, you can follow this guide on how to enable the Firebase products within your application https://firebase.google.com/docs/android/setup#add-config-file

An alternative method is to add Firebase using the Firebase Assistant within Android Studio if it is set up within your IDE. You can view steps on how to register via this option here:
https://firebase.google.com/docs/android/setup#assistant

Once your application is set up within the Firebase Console, you will be able to access the server key required for portal setup. You can access the server key file in JSON format by going into your project overview -> project settings -> Service Account and selecting Generate New Private Key.

![generate-server-key](/img/generate-server-key.png)

The next step is to set up your Android VoIP credentials in the portal.

1. Go to portal.telnyx.com and log in.
2. Go to the API Keys section on the left panel.
3. From the top bar go to the Credentials tab and select “Add” >> Android Credential

![api-keys-pn](/img/api-keys-pn.jpg)

4. Enter the details required for your Android Push Credentials. This includes a Credential name and the generated server key in JSON format in the field Project Account json

![add-android-push-credential](/img/add-android-push-credential-new.png)

Save the new push credential by pressing the Add Push Credential button

We can now attach this Android Push Credential to a SIP Connection:

1. Go to the SIP Connections section on the left panel.
2. Open the Settings menu of the SIP connection that you want to add a Push Credential to or [create a new SIP Connection](/docs/voice/sip-trunking/quickstart).
3. Select the WebRTC tab.
4. Go to the Android Section and select the PN credential you previously created.
