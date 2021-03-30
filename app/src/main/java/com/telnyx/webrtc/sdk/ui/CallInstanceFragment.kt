package com.telnyx.webrtc.sdk.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Chronometer
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.verto.receive.*
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.fragment_call_instance.*
import timber.log.Timber
import java.util.*

// TODO: Rename parameter arguments, choose names that match
private const val CALLER_ID = "callId"


lateinit var mainViewModel: MainViewModel

@SuppressLint("StaticFieldLeak")
lateinit var chronometer: Chronometer

/**
 * A simple [Fragment] subclass.
 * Use the [CallInstanceFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CallInstanceFragment : Fragment(R.layout.fragment_call_instance) {
    private var callId: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            callId = it.getString(CALLER_ID)
        }

        chronometer = requireView().findViewById(R.id.call_timer_id)

        mainViewModel =
            ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setUpOngoingCallButtons(UUID.fromString(callId))
        observeSocketResponses()
    }

    private fun setUpOngoingCallButtons(callId: UUID) {

        mainViewModel.setCurrentCall(callId)

        //Handle call option observers
        mainViewModel.getCallState()?.observe(this.viewLifecycleOwner, { value ->
            requireActivity().call_state_text_value.text = value.name
        })
        mainViewModel.getIsMuteStatus()?.observe(this.viewLifecycleOwner, { value ->
            if (!value) {
                mute_button_id.setImageResource(R.drawable.ic_mic_off)
            } else {
                mute_button_id.setImageResource(R.drawable.ic_mic)
            }
        })

        mainViewModel.getIsOnHoldStatus()?.observe(this.viewLifecycleOwner, { value ->
            if (!value) {
                hold_button_id.setImageResource(R.drawable.ic_hold)
            } else {
                hold_button_id.setImageResource(R.drawable.ic_play)
            }
        })

        mainViewModel.getIsOnLoudSpeakerStatus()?.observe(this.viewLifecycleOwner, { value ->
            if (!value) {
                loud_speaker_button_id.setImageResource(R.drawable.ic_loud_speaker_off)
            } else {
                loud_speaker_button_id.setImageResource(R.drawable.ic_loud_speaker)
            }
        })

        onTimerStart()

        end_call_id.setOnClickListener {
            onEndCall()
        }
        mute_button_id.setOnClickListener {
            mainViewModel.onMuteUnmutePressed()
        }
        hold_button_id.setOnClickListener {
            mainViewModel.onHoldUnholdPressed()
        }
        loud_speaker_button_id.setOnClickListener {
            mainViewModel.onLoudSpeakerPressed()
        }
    }

    private fun onEndCall() {
        mainViewModel.endCall()
        chronometer.stop()
        parentFragmentManager.beginTransaction().remove(this@CallInstanceFragment).commit();


    }

    private fun onTimerStart() {
        chronometer.base = SystemClock.elapsedRealtime()
        chronometer.start()
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()
            ?.observe(this.viewLifecycleOwner, object : SocketObserver<ReceivedMessageBody>() {
                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    when (data?.method) {
                        SocketMethod.INVITE.methodName -> {
                            //NOOP
                        }
                        SocketMethod.BYE.methodName -> {
                            parentFragmentManager.beginTransaction()
                                .remove(this@CallInstanceFragment).commit();
                        }
                    }
                }

                override fun onConnectionEstablished() {
                    //NOOP
                }

                override fun onLoading() {
                    //NOOP
                }

                override fun onError(message: String?) {
                    //NOOP
                }

            })
    }

    companion object {
        /**
         * Use this factory method to create a new instance of
         * this fragment using the provided parameters.
         *
         * @param callId
         * @return A new instance of fragment CallInstanceFragment.
         */
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(callId: String) =
            CallInstanceFragment().apply {
                arguments = Bundle().apply {
                    putString(CALLER_ID, callId)
                }
            }
    }
}