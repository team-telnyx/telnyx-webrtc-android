package com.telnyx.webrtc.sdk.ui

import android.Manifest.permission.*
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.view.Menu
import android.view.View
import android.widget.Toast
import androidx.lifecycle.ViewModelProvider
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import kotlinx.android.synthetic.main.include_login_section.*
import com.telnyx.webrtc.sdk.*
import com.telnyx.webrtc.sdk.manager.UserManager
import com.telnyx.webrtc.sdk.model.Method
import com.telnyx.webrtc.sdk.verto.receive.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.include_call_control_section.*
import kotlinx.android.synthetic.main.include_incoming_call_section.*
import kotlinx.android.synthetic.main.include_ongoing_call_section.*
import kotlinx.android.synthetic.main.video_call_fragment.*
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var userManager: UserManager

    lateinit var mainViewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar_id))

        mainViewModel = ViewModelProvider(this@MainActivity).get(MainViewModel::class.java)


        checkPermissions()
        observeSocketResponses()
        initViews()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.actionbar_menu, menu);
        return true;
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()
                ?.observe(this, object : SocketObserver<ReceivedMessageBody>() {
                    override fun onConnectionEstablished() {
                        onConnectionEstablishedViews()
                    }

                    override fun onMessageReceived(data: ReceivedMessageBody?) {
                        Timber.d("onMessageReceived from SDK [%s]", data?.method)
                        when (data?.method) {
                            Method.LOGIN.methodName -> {
                                val sessionId = (data.result as LoginResponse).sessid
                                onLoginSuccessfullyViews(sessionId)
                            }

                            Method.INVITE.methodName -> {
                                //mainViewModel.playRingtone()
                                val inviteResponse = data.result as InviteResponse
                                onReceiveCallView(
                                        inviteResponse.callId,
                                        inviteResponse.callerIdName,
                                        inviteResponse.callerIdNumber
                                )
                            }

                            Method.ANSWER.methodName -> {
                                val callId = (data.result as AnswerResponse).callId
                                onAnsweredCallViews(callId)
                            }

                            Method.BYE.methodName -> {
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
                    }

                })
    }


    private fun initViews() {
        handleUserLoginState()
        mockInputs()

        connect_button_id.setOnClickListener {
            connectButtonPressed()
        }
        call_button_id.setOnClickListener {
            //ToDo call should do an invite
            mainViewModel.sendInvite(call_input_id.text.toString())
        }
    }

    private fun mockInputs() {
        sip_username_id.setText(MOCK_USERNAME)
        password_id.setText(MOCK_PASSWORD)
        caller_id_name_id.setText(MOCK_CALLER_NAME)
        caller_id_number_id.setText(MOCK_CALLER_NUMBER)
        call_input_id.setText(MOCK_DESTINATION_NUMBER)
    }

    private fun handleUserLoginState() {
        if (!userManager.isUserLogin) {
            login_section_id.visibility = View.VISIBLE
            ongoing_call_section_id.visibility = View.GONE
            incoming_call_section_id.visibility = View.GONE
            call_control_section_id.visibility = View.GONE
        } else {
            login_section_id.visibility = View.GONE
            ongoing_call_section_id.visibility = View.GONE
            incoming_call_section_id.visibility = View.GONE
            call_control_section_id.visibility = View.VISIBLE

            val loginConfig = TelnyxConfig(userManager.sipUsername, userManager.sipPass, userManager.callerIdNumber, userManager.callerIdNumber)
            mainViewModel.doLogin(loginConfig)
        }
    }

    private fun connectButtonPressed() {
        val sipUsername = sip_username_id.text.toString()
        val password = password_id.text.toString()
        val sipCallerName = caller_id_name_id.text.toString()
        val sipCallerNumber = caller_id_number_id.text.toString()

        val loginConfig = TelnyxConfig(sipUsername, password, sipCallerName, sipCallerNumber)
        mainViewModel.doLogin(loginConfig)
    }

    private fun onConnectionEstablishedViews() {
        connect_button_id.isClickable = true
    }

    private fun onLoginSuccessfullyViews(sessionId: String) {
        socket_text_value.text = getString(R.string.connected)
        session_text_value.text = sessionId
        login_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        //Set Shared Preferences now that user has logged in - storing the session:
        mainViewModel.saveUserData(
            sip_username_id.text.toString(),
            password_id.text.toString(),
            caller_id_name_id.text.toString(),
            caller_id_number_id.text.toString()
        )
    }

    private fun onAnsweredCallViews(callId: String) {
        //mainViewModel.stopDialtone()
        setUpOngoingCallButtons(callId)
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.GONE
        ongoing_call_section_id.visibility = View.VISIBLE
        //video_call_section_id.visibility = View.VISIBLE

        onTimerStart()
    }

    private fun onTimerStart() {
        call_timer_id.base = SystemClock.elapsedRealtime()
        call_timer_id.start()
    }

    private fun setUpOngoingCallButtons(callId: String){
        end_call_id.setOnClickListener {
            onRejectCall(callId)
        }
      /*  video_end_call_id.setOnClickListener {
            onRejectCall(callId)
        }
        mute_button_id.setOnClickListener {
            mainViewModel.onMuteUnmutePressed()
        }
        video_mute_button_id.setOnClickListener {
            mainViewModel.onMuteUnmutePressed()
        }
        video_hold_button_id.setOnClickListener {
            mainViewModel.onHoldUnholdPressed(callId)
        }
        video_loud_speaker_button_id.setOnClickListener {
            mainViewModel.onLoudSpeakerPressed()
        }
        video_camera_direction_button_id.setOnClickListener {
            mainViewModel.onCameraDirectionPressed()*
        } */
    }

    private fun onByeReceivedViews() {
        //Stop dialtone in the case of Bye being received as a rejection to the invitation
       // mainViewModel.stopDialtone()
        incoming_call_section_id.visibility = View.GONE
        ongoing_call_section_id.visibility = View.GONE
        video_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE

        call_timer_id.stop()
    }

    private fun onReceiveCallView(callId: String, callerIdName: String, callerIdNumber: String) {
        call_control_section_id.visibility = View.GONE
        incoming_call_section_id.visibility = View.VISIBLE

        answer_call_id.setOnClickListener {
            onAcceptCall(callId, callerIdNumber)
        }
        reject_call_id.setOnClickListener {
            onRejectCall(callId)
        }

        setUpOngoingCallButtons(callId)
    }

    private fun onAcceptCall(callId: String, destinationNumber: String) {
       // mainViewModel.stopRingtone()
       // mainViewModel.acceptCall(callId, destinationNumber)
        setUpOngoingCallButtons(callId)
        incoming_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.GONE
        ongoing_call_section_id.visibility = View.GONE
        video_call_section_id.visibility = View.VISIBLE

       // onTimerStart()
    }

    private fun onRejectCall(callId: String) {
        //Reject call and make call control section visible
        ongoing_call_section_id.visibility = View.GONE
        incoming_call_section_id.visibility = View.GONE
        video_call_section_id.visibility = View.GONE
        call_control_section_id.visibility = View.VISIBLE
        mainViewModel.endCall(callId)
        //reset call timer:
        call_timer_id.stop()
    }

    private fun checkPermissions() {
        Dexter.withContext(this)
            .withPermissions(
                RECORD_AUDIO,
                CAMERA,
                INTERNET,
            )
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    if (report!!.areAllPermissionsGranted()) {
                        mainViewModel.initConnection()
                        Toast.makeText(
                            this@MainActivity,
                            "Granted",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (report.isAnyPermissionPermanentlyDenied) {
                        Toast.makeText(
                            this@MainActivity,
                            "Audio and Camera permissions are required to use the App!",
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