/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

/*package com.telnyx.webrtc.sdk.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.media.ToneGenerator.*
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.davidmiguel.numberkeyboard.NumberKeyboard
import com.davidmiguel.numberkeyboard.NumberKeyboardListener
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.databinding.FragmentCallInstanceBinding
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.verto.receive.*
import timber.log.Timber
import java.util.*


private const val CALLER_ID = "callId"

lateinit var mainViewModel: MainViewModel


/**
 * A simple [Fragment] subclass.
 * Use the [CallInstanceFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class CallInstanceFragment : Fragment(), NumberKeyboardListener {
    private var callId: UUID? = null
    private var _binding: FragmentCallInstanceBinding? = null

    private val binding get() = _binding!!

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCallInstanceBinding.inflate(inflater, container, false)
        val view = binding.root
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arguments?.let {
            callId = UUID.fromString(it.getString(CALLER_ID))
        }

        mainViewModel =
            ViewModelProvider(requireActivity())[MainViewModel::class.java]

        setUpOngoingCallButtons()
        observeSocketResponses()
    }

    private fun setUpOngoingCallButtons() {

        //Handle call option observers
        mainViewModel.getCallState()?.observe(this.viewLifecycleOwner) { value ->
            (requireActivity() as MainActivity).callStateTextValue?.text = value.name
        }
        mainViewModel.getIsMuteStatus()?.observe(this.viewLifecycleOwner) { value ->
            binding.apply {
                if (!value) {
                    muteButtonId.setImageResource(R.drawable.ic_mic_off)
                } else {
                    muteButtonId.setImageResource(R.drawable.ic_mic)
                }
            }
        }

        mainViewModel.getIsOnHoldStatus()?.observe(this.viewLifecycleOwner) { value ->
            binding.apply {
                if (!value) {
                    holdButtonId.setImageResource(R.drawable.ic_hold)
                } else {
                    holdButtonId.setImageResource(R.drawable.ic_play)
                }
            }
        }

        mainViewModel.getIsOnLoudSpeakerStatus()?.observe(this.viewLifecycleOwner) { value ->
            binding.apply {
                if (!value) {
                    loudSpeakerButtonId.setImageResource(R.drawable.ic_loud_speaker_off)
                } else {
                    loudSpeakerButtonId.setImageResource(R.drawable.ic_loud_speaker)
                }
            }
        }

        onTimerStart()

        binding.apply {
            endCallId.setOnClickListener {
                onEndCall()
            }
            muteButtonId.setOnClickListener {
                mainViewModel.onMuteUnmutePressed()
            }
            holdButtonId.setOnClickListener {
                mainViewModel.onHoldUnholdPressed(callId!!)
            }
            loudSpeakerButtonId.setOnClickListener {
                mainViewModel.onLoudSpeakerPressed()
            }
            dialPadButtonId.setOnClickListener {
                dialpadSectionId.root.visibility = View.VISIBLE
                val numberKeyboard = view?.findViewById<NumberKeyboard>(R.id.dialpad_id)
                numberKeyboard?.setListener(this@CallInstanceFragment)
            }
        }


    }

    private fun onEndCall() {
        mainViewModel.endCall(callId!!)
        binding.callTimerId.stop()
        parentFragmentManager.beginTransaction().remove(this@CallInstanceFragment).commit();
    }

    private fun onTimerStart() {
        binding.apply {
            callTimerId.base = SystemClock.elapsedRealtime()
            callTimerId.start()
        }
    }

    private fun observeSocketResponses() {
        mainViewModel.getSocketResponse()
            ?.observe(this.viewLifecycleOwner, object : SocketObserver<ReceivedMessageBody>() {
                override fun onMessageReceived(data: ReceivedMessageBody?) {
                    when (data?.method) {
                        SocketMethod.INVITE.methodName -> {
                            //NOOP
                        }
                        SocketMethod.RINGING.methodName -> {

                        }
                        SocketMethod.MEDIA.methodName -> {

                        }
                        SocketMethod.BYE.methodName -> {

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

                override fun onSocketDisconnect() {
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
        @JvmStatic
        fun newInstance(callId: String) =
            CallInstanceFragment().apply {
                arguments = Bundle().apply {
                    putString(CALLER_ID, callId)
                }
            }
    }

    override fun onLeftAuxButtonClicked() {
        //NOOP
    }

    override fun onNumberClicked(number: Int) {
        mainViewModel.dtmfPressed(callId!!, number.toString())
        when (number) {
            0 -> {
                toneGenerator.startTone(TONE_DTMF_0, 500)
            }
            1 -> {
                toneGenerator.startTone(TONE_DTMF_1, 500)
            }
            2 -> {
                toneGenerator.startTone(TONE_DTMF_2, 500)
            }
            3 -> {
                toneGenerator.startTone(TONE_DTMF_3, 500)
            }
            4 -> {
                toneGenerator.startTone(TONE_DTMF_4, 500)
            }
            5 -> {
                toneGenerator.startTone(TONE_DTMF_5, 500)
            }
            6 -> {
                toneGenerator.startTone(TONE_DTMF_6, 500)
            }
            7 -> {
                toneGenerator.startTone(TONE_DTMF_7, 500)
            }
            8 -> {
                toneGenerator.startTone(TONE_DTMF_8, 500)
            }
            9 -> {
                toneGenerator.startTone(TONE_DTMF_9, 500)
            }
        }

    }

    override fun onRightAuxButtonClicked() {
       binding.dialpadSectionId.root.visibility = View.INVISIBLE
    }
}*/