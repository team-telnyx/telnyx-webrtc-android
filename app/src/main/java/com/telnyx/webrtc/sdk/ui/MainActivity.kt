/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.ui

import android.Manifest.permission.*
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
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
import com.telnyx.webrtc.sdk.*
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.AudioDevice
import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.verto.receive.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.include_call_control_section.*
import kotlinx.android.synthetic.main.include_incoming_call_section.*
import kotlinx.android.synthetic.main.include_login_credential_section.*
import kotlinx.android.synthetic.main.include_login_section.*
import kotlinx.android.synthetic.main.include_login_token_section.*
import timber.log.Timber
import java.io.IOException
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private var invitationSent: Boolean = false

    @Inject
    lateinit var userManager: UserManager

    lateinit var mainViewModel: MainViewModel

    private var fcmToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar_id))

        FirebaseApp.initializeApp(this);

        mainViewModel = ViewModelProvider(this@MainActivity).get(MainViewModel::class.java)

        checkPermissions()
        initViews()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.actionbar_menu, menu);
        return true;
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_disconnect -> {
            if (userManager.isUserLogin) {
                disconnectPressed()
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
        else -> {
            super.onOptionsItemSelected(item)
        }
    }


    private fun createAudioOutputSelectionDialog(): Dialog {
        return this.let {
            val audioOutputList = arrayOf("Phone", "Bluetooth", "Loud Speaker")
            val builder = AlertDialog.Builder(this)
            //Set default to phone
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

    private fun connectToSocketAndObserve() {
        mainViewModel.initConnection(applicationContext)
        observeSocketResponses()
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()
            ?.observe(this, object : SocketObserver<ReceivedMessageBody>() {
                override fun onConnectionEstablished() {
                    doLogin()
                }

                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    Timber.d("onMessageReceived from SDK [%s]", data?.method)
                    when (data?.method) {
                        SocketMethod.LOGIN.methodName -> {
                            progress_indicator_id.visibility = View.INVISIBLE
                            val sessionId = (data.result as LoginResponse).sessid
                            Timber.d("Current Session: $sessionId")
                            onLoginSuccessfullyViews()
                        }

                        SocketMethod.INVITE.methodName -> {
                            val inviteResponse = data.result as InviteResponse
                            onReceiveCallView(
                                inviteResponse.callId,
                                inviteResponse.callerIdName,
                                inviteResponse.callerIdNumber
                            )
                        }

                        SocketMethod.ANSWER.methodName -> {
                            val callId = (data.result as AnswerResponse).callId
                            launchCallInstance(callId)
                            call_button_id.visibility = View.VISIBLE
                            cancel_call_button_id.visibility = View.GONE
                            invitationSent = false
                        }

                        SocketMethod.BYE.methodName -> {
                            onByeReceivedViews()
                        }
                    }
                }

                override fun onLoading() {
                    //todo: Show loading in case problem for connecting
                }

                override fun onError(message: String?) {
                    Toast.makeText(
                        this@MainActivity,
                        message ?: "Socket Connection Error",
                        Toast.LENGTH_SHORT
                    ).show()
                    progress_indicator_id.visibility = View.INVISIBLE
                }

            })
    }

    private fun initViews() {
        mockInputs()
        handleUserLoginState()
        getFCMToken()

        connect_button_id.setOnClickListener {
            if (!hasLoginEmptyFields()) {
                connectButtonPressed()
            }
        }
        call_button_id.setOnClickListener {
            mainViewModel.sendInvite(
                userManager.calledIdName,
                userManager.callerIdNumber,
                call_input_id.text.toString(),
                "Sample Client State"
            )
            call_button_id.visibility = View.GONE
            cancel_call_button_id.visibility = View.VISIBLE
        }
        cancel_call_button_id.setOnClickListener {
            mainViewModel.endCall()
            call_button_id.visibility = View.VISIBLE
            cancel_call_button_id.visibility = View.GONE
        }
    }

    private fun hasLoginEmptyFields(): Boolean {
        var hasEmptyFileds = false
        if (token_login_switch.isChecked) {
            if (sip_token_id.text.isEmpty()) {
                showEmptyFieldsToast()
                hasEmptyFileds = true
            }
        } else {
            if (sip_username_id.text.isEmpty() || sip_password_id.text.isEmpty()) {
                showEmptyFieldsToast()
                hasEmptyFileds = true
            }
        }
        return hasEmptyFileds
    }

    private fun showEmptyFieldsToast() {
        Toast.makeText(this, getString(R.string.empty_msj_toast), Toast.LENGTH_LONG).show()
    }

    private fun mockInputs() {
        sip_username_id.setText(BuildConfig.MOCK_USERNAME)
        sip_password_id.setText(BuildConfig.MOCK_PASSWORD)
        caller_id_name_id.setText(MOCK_CALLER_NAME)
        caller_id_number_id.setText(MOCK_CALLER_NUMBER)
        call_input_id.setText(MOCK_DESTINATION_NUMBER)
    }

    private fun handleUserLoginState() {
        listenLoginTypeSwitch()
        if (!userManager.isUserLogin) {
            login_section_id.visibility = View.VISIBLE
            incoming_call_section_id.visibility = View.GONE
            call_control_section_id.visibility = View.GONE
        } else {
            val loginConfig = CredentialConfig(
                userManager.sipUsername,
                userManager.sipPass,
                userManager.callerIdNumber,
                userManager.callerIdNumber,
                userManager.fcmToken,
                R.raw.incoming_call,
                R.raw.ringback_tone,
                LogLevel.ALL
            )
            mainViewModel.doLoginWithCredentials(loginConfig)
        }
    }

    private fun listenLoginTypeSwitch() {
        token_login_switch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                login_credential_id.visibility = View.GONE
                login_token_id.visibility = View.VISIBLE
            } else {
                login_credential_id.visibility = View.VISIBLE
                login_token_id.visibility = View.GONE
            }
        }
    }

    private fun connectButtonPressed() {
        progress_indicator_id.visibility = View.VISIBLE
        connectToSocketAndObserve()
    }

    private fun doLogin() {
        //path to ringtone and ringBackTone
        val ringtone = R.raw.incoming_call
        val ringBackTone = R.raw.ringback_tone

        if (token_login_switch.isChecked) {
            val sipToken = sip_token_id.text.toString()
            val sipCallerName = token_caller_id_name_id.text.toString()
            val sipCallerNumber = token_caller_id_number_id.text.toString()

            val loginConfig = TokenConfig(
                sipToken,
                sipCallerName,
                sipCallerNumber,
                fcmToken,
                ringtone,
                ringBackTone,
                LogLevel.ALL
            )

            mainViewModel.doLoginWithToken(loginConfig)

        } else {
            val sipUsername = sip_username_id.text.toString()
            val password = sip_password_id.text.toString()
            val sipCallerName = caller_id_name_id.text.toString()
            val sipCallerNumber = caller_id_number_id.text.toString()

            val loginConfig = CredentialConfig(
                sipUsername,
                password,
                sipCallerName,
                sipCallerNumber,
                fcmToken,
                ringtone,
                ringBackTone,
                LogLevel.ALL
            )
            mainViewModel.doLoginWithCredentials(loginConfig)
        }
    }

    private fun getFCMToken() {
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                Timber.d("Fetching FCM registration token failed")
                fcmToken = null
            }

            // Get new FCM registration token
            var token = ""
            try {
                 token = task.result
            } catch (e: IOException) {
                Timber.d(e)
            }
            Timber.d("FCM TOKEN RECEIVED: $token")
            fcmToken = token
        }
    }

    private fun disconnectPressed() {
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.GONE
        login_section_id.visibility = View.VISIBLE

        socket_text_value.text = getString(R.string.disconnected)
        call_state_text_value.text = "-"

        mainViewModel.disconnect()
    }


    private fun onLoginSuccessfullyViews() {
        socket_text_value.text = getString(R.string.connected)
        login_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        //Dont store login details if logged in via a token
        if (!token_login_switch.isChecked) {
            //Set Shared Preferences now that user has logged in - storing the session:
            mainViewModel.saveUserData(
                sip_username_id.text.toString(),
                sip_password_id.text.toString(),
                fcmToken,
                caller_id_name_id.text.toString(),
                caller_id_number_id.text.toString()
            )
        }
    }

    private fun launchCallInstance(callId: UUID) {
        mainViewModel.setCurrentCall(callId)

        val callInstanceFragment = CallInstanceFragment.newInstance(callId.toString())
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_call_instance, callInstanceFragment)
            .commit()
    }

    private fun onByeReceivedViews() {
        invitationSent = false
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE
        call_button_id.visibility = View.VISIBLE
        cancel_call_button_id.visibility = View.GONE
    }

    private fun onReceiveCallView(callId: UUID, callerIdName: String, callerIdNumber: String) {
        call_control_section_id.visibility = View.GONE
        incoming_call_section_id.visibility = View.VISIBLE
        incoming_call_section_id.bringToFront()

        mainViewModel.setCurrentCall(callId)

        answer_call_id.setOnClickListener {
            onAcceptCall(callId, callerIdNumber)
        }
        reject_call_id.setOnClickListener {
            onRejectCall(callId)
        }
    }

    private fun onAcceptCall(callId: UUID, destinationNumber: String) {
        incoming_call_section_id.visibility = View.GONE
        //Visible but underneath fragment
        call_control_section_id.visibility = View.VISIBLE

        mainViewModel.acceptCall(callId, destinationNumber)
        launchCallInstance(callId)
    }

    private fun onRejectCall(callId: UUID) {
        //Reject call and make call control section visible
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        mainViewModel.endCall(callId)
    }

    private fun checkPermissions() {
        Dexter.withContext(this)
            .withPermissions(
                RECORD_AUDIO,
                INTERNET,
            )
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    if (report!!.areAllPermissionsGranted()) {
                        connect_button_id.isClickable = true
                    } else if (report.isAnyPermissionPermanentlyDenied) {
                        Toast.makeText(
                            this@MainActivity,
                            "Audio permissions are required to continue",
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