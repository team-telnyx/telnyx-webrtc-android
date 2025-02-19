/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.Manifest
import android.Manifest.permission.INTERNET
import android.Manifest.permission.RECORD_AUDIO
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.telnyx.webrtc.sdk.BuildConfig
import com.telnyx.webrtc.sdk.CredentialConfig
import com.telnyx.webrtc.sdk.MOCK_CALLER_NAME
import com.telnyx.webrtc.sdk.MOCK_CALLER_NUMBER
import com.telnyx.webrtc.sdk.MOCK_DESTINATION_NUMBER
import com.telnyx.webrtc.sdk.MOCK_PASSWORD
import com.telnyx.webrtc.sdk.MOCK_USERNAME
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.TokenConfig
import com.telnyx.webrtc.sdk.databinding.ActivityMainBinding
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.TxServerConfiguration
import com.telnyx.webrtc.sdk.ui.wsmessages.WsMessageFragment
import com.telnyx.webrtc.sdk.utility.telecom.call.TelecomCallService
import com.telnyx.webrtc.sdk.verto.receive.AnswerResponse
import com.telnyx.webrtc.sdk.verto.receive.ByeResponse
import com.telnyx.webrtc.sdk.verto.receive.InviteResponse
import com.telnyx.webrtc.sdk.verto.receive.LoginResponse
import com.telnyx.webrtc.sdk.verto.receive.ReceivedMessageBody
import com.telnyx.webrtc.sdk.verto.receive.SocketObserver
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var callControlView: View
    private lateinit var incomingCallView: View
    private lateinit var loginSectionView: View
    var callStateTextValue: TextView? = null

    @Inject
    lateinit var userManager: UserManager
    private var invitationSent: Boolean = false
    private lateinit var mainViewModel: MainViewModel
    private var fcmToken: String? = null
    private var isDev = false
    private var isAutomaticLogin = false
    private var wsMessageList: ArrayList<String>? = null
    private var credentialConfig: CredentialConfig? = null
    private var tokenConfig: TokenConfig? = null

    // Notification handling
    private var txPushMetaData: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view: View = binding.root
        mainViewModel = ViewModelProvider(this@MainActivity)[MainViewModel::class.java]


        setContentView(view)
        binding.apply {
            callControlView = callControlSectionId.callControlView
            incomingCallView = incomingActiveCallSectionId.incomingView
            loginSectionView = loginSectionId.loginSectionView
            this@MainActivity.callStateTextValue = callStateTextValue
        }

        binding.toolbarId.setOnMenuItemClickListener {
            onOptionsItemSelected(it)
        }

        // Add environment text
        isDev = userManager.isDev
        updateEnvText(isDev)

        FirebaseApp.initializeApp(this)


        checkPermissions()
        initViews()
        handleServiceIntent(intent)
        handleUserLoginState()
        binding.toolbarId.setOnMenuItemClickListener(this::onOptionsItemSelected)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        Timber.d("onCreateOptionsMenu")
        menuInflater.inflate(R.menu.actionbar_menu, menu)
        return true
    }


    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        Timber.d("onOptionsItemSelected ${item.itemId}")
        return when (item.itemId) {
            R.id.action_disconnect -> {
                if (userManager.isUserLogin) {
                    disconnectPressed()
                    isAutomaticLogin = false
                } else {
                    Toast.makeText(this, "Not connected", Toast.LENGTH_SHORT).show()
                }

                true
            }

            R.id.action_change_audio_output -> {
                val dialog = createAudioOutputSelectionDialog()
                dialog.show()
                true
            }

            R.id.action_wsmessages -> {
                if (wsMessageList == null) {
                    wsMessageList = ArrayList()
                }
                val instanceFragment = WsMessageFragment.newInstance(wsMessageList)
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, instanceFragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
                true
            }

            else -> {
                super.onOptionsItemSelected(item)
            }
        }
    }

    private fun createAudioOutputSelectionDialog(): Dialog {
        return this.let {
            val audioOutputList = arrayOf("Phone", "Bluetooth", "Loud Speaker")
            val builder = AlertDialog.Builder(this)

            // Set default to phone
            mainViewModel.changeAudioOutput(AudioDevice.PHONE_EARPIECE)
            builder.setTitle("Select Audio Output")
            builder.setSingleChoiceItems(
                audioOutputList, 0
            ) { _, which ->
                when (which) {
                    0 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.PHONE_EARPIECE)
                    }

                    1 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.BLUETOOTH)
                    }

                    2 -> {
                        mainViewModel.changeAudioOutput(AudioDevice.LOUDSPEAKER)
                    }
                }
            }
                // Set the action buttons
                .setNeutralButton(
                    "ok"
                ) { dialog, _ ->
                    dialog.dismiss()
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun connectToSocketAndObserve(txPushMetaData: String? = null) {

        Timber.d("doLogin")
        // path to ringtone and ringBackTone
        val ringtone = R.raw.incoming_call
        val ringBackTone = R.raw.ringback_tone

        if (userManager.isUserLogin) {
            val loginConfig = CredentialConfig(
                userManager.sipUsername,
                userManager.sipPass,
                userManager.callerIdNumber,
                userManager.callerIdNumber,
                userManager.fcmToken,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),// or ringtone,
                R.raw.ringback_tone,
                LogLevel.ALL,
                debug = false
            )
            credentialConfig = loginConfig
        } else {
            binding.loginSectionId.apply {
                if (tokenLoginSwitch.isChecked) {
                    loginTokenId.apply {
                        val sipToken = sipTokenId.text.toString()
                        val sipCallerName = tokenCallerIdNameId.text.toString()
                        val sipCallerNumber = tokenCallerIdNumberId.text.toString()

                        val loginConfig = TokenConfig(
                            sipToken,
                            sipCallerName,
                            sipCallerNumber,
                            fcmToken,
                            ringtone,
                            ringBackTone,
                            LogLevel.ALL,
                            debug = BuildConfig.IS_STATS_DEBUG_MODE
                        )
                        tokenConfig = loginConfig
                    }
                } else {
                    loginCredentialId.apply {
                        val sipUsername = sipUsernameId.text.toString()
                        val password = sipPasswordId.text.toString()
                        val sipCallerName = callerIdNameId.text.toString()
                        val sipCallerNumber = callerIdNumberId.text.toString()

                        val loginConfig = CredentialConfig(
                            sipUsername,
                            password,
                            sipCallerName,
                            sipCallerNumber,
                            fcmToken,
                            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), // or ringtone,
                            ringBackTone,
                            LogLevel.ALL,
                            debug = false
                        )
                        credentialConfig = loginConfig
                    }
                }
            }

        }
        Timber.d("Connect to Socket and Observe")
        if (!isDev) {
            mainViewModel.initConnection(
                null,
                credentialConfig = credentialConfig,
                tokenConfig = tokenConfig,
                txPushMetaData
            )
        } else {
            mainViewModel.initConnection(
                TxServerConfiguration(host = "rtcdev.telnyx.com"),
                credentialConfig = credentialConfig,
                tokenConfig = tokenConfig,
                txPushMetaData
            )

        }
        observeSocketResponses()
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()?.observe(
            this,
            object : SocketObserver<ReceivedMessageBody>() {
                override fun onConnectionEstablished() {
                    Timber.d("OnConMan")

                }

                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    Timber.d("onMessageReceived from SDK [%s]", data?.method)
                    when (data?.method) {
                        SocketMethod.CLIENT_READY.methodName -> {
                            Timber.d("You are ready to make calls.")

                        }

                        SocketMethod.LOGIN.methodName -> {
                            binding.progressIndicatorId.visibility = View.INVISIBLE
                            val sessionId = (data.result as LoginResponse).sessid
                            Timber.d("Current Session: $sessionId")
                            onLoginSuccessfullyViews()
                        }

                        SocketMethod.INVITE.methodName -> {
                            val inviteResponse = data.result as InviteResponse
                            showIncomingCall(
                                inviteResponse.callerIdName,
                                inviteResponse.callerIdNumber,
                                inviteResponse.callId
                            )
                        }

                        SocketMethod.ANSWER.methodName -> {
                            binding.apply {
                                callControlSectionId.callButtonId.visibility =
                                    View.VISIBLE
                                callControlSectionId.cancelCallButtonId.visibility =
                                    View.GONE
                            }

                            invitationSent = false
                        }

                        SocketMethod.RINGING.methodName -> {
                            // Client Can simulate ringing state
                        }

                        SocketMethod.MEDIA.methodName -> {
                            // Ringback tone is streamed to the caller
                            // early Media -  Client Can simulate ringing state
                        }

                        SocketMethod.BYE.methodName -> {
                            onByeReceivedViews()
                            val callId = (data.result as ByeResponse).callId
                            mainViewModel.onByeReceived(callId)
                        }
                    }
                }

                override fun onLoading() {
                    Timber.i("Loading...")
                }

                override fun onError(message: String?) {
                    Timber.e("onError: %s", message)
                    Toast.makeText(
                        this@MainActivity,
                        message ?: "Socket Connection Error",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onSocketDisconnect() {
                    Toast.makeText(
                        this@MainActivity,
                        "Socket is disconnected",
                        Toast.LENGTH_SHORT
                    ).show()

                    binding.apply {
                        progressIndicatorId.visibility = View.INVISIBLE
                        incomingCallView.visibility = View.GONE
                        callControlView.visibility = View.GONE
                        loginSectionView.visibility = View.VISIBLE

                        socketTextValue.text = getString(R.string.disconnected)
                        callStateTextValue.text = "-"
                    }


                }
            }
        )
    }

    private fun observeWsMessage() {
        mainViewModel.getWsMessageResponse()?.observe(this) {
            it?.let { wsMesssage ->
                wsMessageList?.add(wsMesssage.toString())
            }
        }
    }

    private fun updateEnvText(isDevEnvironment: Boolean) {
        binding.apply {
            if (isDevEnvironment) {
                environmentText.text = "Dev"
            } else {
                environmentText.text = "Prod"
            }
        }

    }

    private fun initViews() {
        mockInputs()
        getFCMToken()
        observeWsMessage()

        binding.loginSectionId.connectButtonId.setOnClickListener {
            if (!hasLoginEmptyFields()) {
                connectButtonPressed()
            }
        }
        binding.callControlSectionId.apply {
            callButtonId.setOnClickListener {
                placeOutgoingCall(
                    userManager.callerIdName,
                    callInputId.text.toString(),
                    UUID.randomUUID()
                )
            }
        }
        binding.callControlSectionId.apply {
            cancelCallButtonId.setOnClickListener {
                // mainViewModel.endCall()
                callButtonId.visibility = View.VISIBLE
                cancelCallButtonId.visibility = View.GONE
            }
        }

        binding.telnyxImageId.setOnLongClickListener {
            onCreateSecretMenuDialog().show()
            true
        }
    }

    private fun onCreateSecretMenuDialog(): Dialog {
        return this.let {
            val secretOptionList = arrayOf(
                "Development Environment",
                "Production Environment",
                "Copy Firebase Instance Token"
            )
            val builder = AlertDialog.Builder(it)
            builder.setTitle("Select Secret Setting")
                .setItems(
                    secretOptionList
                ) { _, which ->
                    when (which) {
                        0 -> {
                            // Switch to Dev
                            isDev = true
                            userManager.isDev = true
                            updateEnvText(isDev)
                            Toast.makeText(this, "Switched to DEV environment", Toast.LENGTH_LONG)
                                .show()
                        }

                        1 -> {
                            // Switch to Prod
                            isDev = false
                            userManager.isDev = false
                            updateEnvText(isDev)
                            Toast.makeText(this, "Switched to PROD environment", Toast.LENGTH_LONG)
                                .show()
                        }

                        2 -> {
                            val clipboardManager =
                                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = ClipData.newPlainText("text", fcmToken)
                            clipboardManager.setPrimaryClip(clipData)
                            Toast.makeText(this, "FCM Token copied to clipboard", Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                }
            builder.create()
        } ?: throw IllegalStateException("Activity cannot be null")
    }

    private fun hasLoginEmptyFields(): Boolean {
        var hasEmptyFields = false

        binding.loginSectionId.apply {
            if (tokenLoginSwitch.isChecked) {
                if (binding.loginSectionId.loginTokenId.sipTokenId.text.isEmpty()) {
                    showEmptyFieldsToast()
                    hasEmptyFields = true
                }
            } else {
                binding.loginSectionId.loginCredentialId.apply {
                    if (sipUsernameId.text.isEmpty() || sipPasswordId.text.isEmpty()) {
                        showEmptyFieldsToast()
                        hasEmptyFields = true
                    }
                }
            }
        }
        return hasEmptyFields
    }

    private fun showEmptyFieldsToast() {
        Toast.makeText(this, getString(R.string.empty_msg_toast), Toast.LENGTH_LONG).show()
    }

    private fun mockInputs() {
        binding.loginSectionId.loginCredentialId.apply {
            sipUsernameId.setText(MOCK_USERNAME)
            sipPasswordId.setText(MOCK_PASSWORD)
            callerIdNameId.setText(MOCK_CALLER_NAME)
            callerIdNumberId.setText(MOCK_CALLER_NUMBER)
            binding.callControlSectionId.callInputId.setText(MOCK_DESTINATION_NUMBER)
        }

    }

    private fun handleUserLoginState() {
        listenLoginTypeSwitch()
        if (!userManager.isUserLogin) {
            binding.loginSectionId.loginSectionView.visibility = View.VISIBLE
            incomingCallView.visibility = View.GONE
            callControlView.visibility = View.GONE
        } else {
            isAutomaticLogin = true
            connectButtonPressed()
        }
    }

    private fun listenLoginTypeSwitch() {
        binding.loginSectionId.tokenLoginSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                binding.loginSectionId.loginSectionView.visibility = View.GONE
                binding.loginSectionId.loginTokenId.loginTokenSectionView.visibility = View.VISIBLE
            } else {
                binding.loginSectionId.loginCredentialId.credentialLoginView.visibility =
                    View.VISIBLE
                binding.loginSectionId.loginTokenId.loginTokenSectionView.visibility = View.GONE
            }
        }
    }

    private fun connectButtonPressed() {
        binding.progressIndicatorId.visibility = View.VISIBLE
        Timber.d("notificationAcceptHandling is true $txPushMetaData")
        if (txPushMetaData != null) {
            connectToSocketAndObserve(txPushMetaData)
        } else {
            connectToSocketAndObserve()
        }
    }

    private fun getFCMToken() {
        var token = ""
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.d("Fetching FCM registration token failed")
                fcmToken = null
            } else if (task.isSuccessful) {
                // Get new FCM registration token
                try {
                    token = task.result.toString()
                } catch (e: IOException) {
                    Timber.d(e)
                }
                Timber.d("FCM TOKEN RECEIVED: $token")
            }
            fcmToken = token

        }
    }

    private fun disconnectPressed() {
        mainViewModel.disconnect()
    }

    private fun onLoginSuccessfullyViews() {
        binding.apply {
            socketTextValue.text = getString(R.string.connected)
            loginSectionId.loginSectionView.visibility = View.GONE
            callControlSectionId.callControlView.visibility = View.VISIBLE

            loginSectionId.apply {
                // Don't store login details if logged in via a token
                if (!tokenLoginSwitch.isChecked) {
                    loginCredentialId.apply {
                        // Set Shared Preferences now that user has logged in - storing the session:
                        mainViewModel.saveUserData(
                            sipUsernameId.text.toString(),
                            sipPasswordId.text.toString(),
                            fcmToken,
                            callerIdNameId.text.toString(),
                            callerIdNumberId.text.toString(),
                            isDev
                        )
                    }

                }
            }
        }


    }

    private fun onByeReceivedViews() {
        invitationSent = false
        binding.apply {
            incomingCallSectionId.root.visibility = View.GONE
            callControlSectionId.root.visibility = View.VISIBLE
            callControlSectionId.callButtonId.visibility = View.VISIBLE
            callControlSectionId.cancelCallButtonId.visibility = View.GONE
            incomingActiveCallSectionId.root.visibility = View.GONE
        }
    }


    //ToDo(Oli): Verify that we no longer need this. Connection Service should be handling this now
    /*private fun onReceiveCallView(callId: UUID, callerIdNumber: String) {
        if (mainViewModel.currentCall?.callStateFlow?.value == CallState.ACTIVE) {
            onReceiveActiveCallView(callId, callerIdNumber)
            return
        }

        mainViewModel.currentCall
        mainViewModel.setCurrentCall(callId)
        when (notificationAcceptHandling) {
            true -> {
                Thread.sleep(1000)
                onAcceptCall(callId, callerIdNumber)
                notificationAcceptHandling = null
            }

            false -> {
                onRejectCall(callId)
                notificationAcceptHandling = null
            }

            else -> {
                binding.apply {
                    callControlSectionId.root.visibility = View.GONE
                    incomingCallSectionId.root.visibility = View.VISIBLE
                    incomingCallSectionId.root.bringToFront()
                    incomingCallSectionId.answerCallId.setOnClickListener {
                        onAcceptCall(callId, callerIdNumber)
                    }
                    incomingCallSectionId.rejectCallId.setOnClickListener {
                        onRejectCall(callId)
                    }
                }

            }
        }
    }*/

    /*private fun onReceiveActiveCallView(callId: UUID, callerIdNumber: String) {
        binding.apply {
            callControlSectionId.root.visibility = View.GONE
            incomingActiveCallSectionId.root.visibility = View.VISIBLE
            incomingActiveCallSectionId.root.bringToFront()

            incomingActiveCallSectionId.endAndAccept.setOnClickListener {
                mainViewModel.currentCall!!.let {
                    isActiveBye = true
                    it.endCall(it.callId)
                }.also {
                    onAcceptCall(callId, callerIdNumber)
                }
            }
            incomingActiveCallSectionId.rejectCurrentCall.setOnClickListener {
                onRejectActiveCall(callId)
            }
            incomingActiveCallSectionId.holdAndAccept.setOnClickListener {
                mainViewModel.currentCall?.let {
                    mainViewModel.onHoldUnholdPressed(it.callId)
                }.also {
                    onAcceptCall(callId, callerIdNumber)
                }
            }
        }

    }


    private fun onAcceptCall(callId: UUID, destinationNumber: String) {
        binding.apply {
            incomingCallSectionId.root.visibility = View.GONE
            incomingActiveCallSectionId.root.visibility = View.GONE
            // Visible but underneath fragment
            callControlSectionId.root.visibility = View.VISIBLE

        }

        launchCallInstance(callId)
        mainViewModel.acceptCall(callId, destinationNumber)

    }

    private fun onRejectActiveCall(callId: UUID) {
        // Reject call and make call control section visible
        binding.incomingActiveCallSectionId.root.visibility = View.GONE
        mainViewModel.endCall(callId)
    }

    private fun onRejectCall(callId: UUID) {
        // Reject call and make call control section visible
        binding.apply {
            incomingCallSectionId.root.visibility = View.GONE
            callControlSectionId.root.visibility = View.VISIBLE
        }


        mainViewModel.endCall(callId)
    }*/

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dexter.withContext(this)
                .withPermissions(
                    RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            binding.loginSectionId.connectButtonId.isClickable = true
                        } else if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "permissions are required to continue",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permission: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        } else {
            Dexter.withContext(this)
                .withPermissions(
                    RECORD_AUDIO,
                    INTERNET
                )
                .withListener(object : MultiplePermissionsListener {
                    override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                        if (report!!.areAllPermissionsGranted()) {
                            binding.loginSectionId.connectButtonId.isClickable = true
                        } else if (report.isAnyPermissionPermanentlyDenied) {
                            Toast.makeText(
                                this@MainActivity,
                                "permissions are required to continue",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permission: MutableList<PermissionRequest>?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        }
    }

    private fun handleCallNotification(intent: Intent?) {

        if (intent == null) {
            Timber.d("Intent is null")
            return
        }

        /*Timber.d("onNewIntent ")
        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            putExtra("action", NotificationsService.STOP_ACTION)
        }
        serviceIntent.setAction(NotificationsService.STOP_ACTION)
        startService(serviceIntent)

        val action = intent.extras?.getString(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)

        action?.let {
            txPushMetaData = intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)
            Timber.d("Action: $action  ${txPushMetaData ?: "No Metadata"}")
            if (action == MyFirebaseMessagingService.ACT_ANSWER_CALL) {
                // Handle Answer
                notificationAcceptHandling = true
                Timber.d("Call answered from notification")
            } else if (action == MyFirebaseMessagingService.ACT_REJECT_CALL) {
                // Handle Reject
                notificationAcceptHandling = false
                Timber.d("Call rejected from notification")
            }
            connectButtonPressed()
        }*/
    }

    override fun onResume() {
        super.onResume()
        Timber.d("onResume")
    }

    override fun onStop() {
        super.onStop()
        Timber.d("onStop")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleServiceIntent(intent)
    }

    private fun handleServiceIntent(intent: Intent?) {
        handleCallNotification(intent)
    }
}

private fun Context.placeOutgoingCall(
    displayName: String,
    phoneNumber: String,
    telnyxCallId: UUID
) {
    startService(
        Intent(this, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_OUTGOING_CALL
            putExtra(TelecomCallService.EXTRA_NAME, displayName)
            putExtra(TelecomCallService.EXTRA_URI, Uri.fromParts("tel", phoneNumber, null))
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, telnyxCallId.toString())
        },
    )
}

private fun Context.showIncomingCall(
    displayName: String,
    phoneNumber: String,
    telnyxCallId: UUID
) {
    startService(
        Intent(this, TelecomCallService::class.java).apply {
            action = TelecomCallService.ACTION_INCOMING_CALL
            putExtra(TelecomCallService.EXTRA_NAME, displayName)
            putExtra(TelecomCallService.EXTRA_URI, Uri.fromParts("tel", phoneNumber, null))
            putExtra(TelecomCallService.EXTRA_TELNYX_CALL_ID, telnyxCallId.toString())
        },
    )
}
