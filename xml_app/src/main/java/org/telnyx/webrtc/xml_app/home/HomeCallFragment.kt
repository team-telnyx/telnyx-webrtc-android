package org.telnyx.webrtc.xml_app.home

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.FragmentHomeCallBinding
import org.telnyx.webrtc.xml_app.login.DialpadFragment
import java.util.*


class HomeCallFragment : Fragment() {

    private var _binding: FragmentHomeCallBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private lateinit var dialpadFragment: DialpadFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeCallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialpadFragment = DialpadFragment()

        setupUI()
        bindEvents()
    }

    private fun setupUI() {
        binding.call.setOnClickListener {
            binding.callInput.text?.let { editable ->
                if (editable.isNotEmpty()) {
                    telnyxViewModel.sendInvite(this@HomeCallFragment.requireContext(), editable.trim().toString())
                }
            }
        }

        binding.mute.setOnClickListener {
            telnyxViewModel.currentCall?.onMuteUnmutePressed()
        }

        binding.endCall.setOnClickListener {
            telnyxViewModel.endCall(this@HomeCallFragment.requireContext())
        }

        binding.loudSpeaker.setOnClickListener {
            telnyxViewModel.currentCall?.onLoudSpeakerPressed()
        }

        binding.disconnect.setOnClickListener {
            telnyxViewModel.disconnect(this@HomeCallFragment.requireContext())
        }

        binding.hold.setOnClickListener {
            telnyxViewModel.holdUnholdCurrentCall(this@HomeCallFragment.requireContext())
        }

        binding.dialpad.setOnClickListener {
            if (!dialpadFragment.isAdded) {
                dialpadFragment.show(
                    requireActivity().supportFragmentManager,
                    "dialpadFragment"
                )
            }
        }
    }

    private fun bindEvents() {
        lifecycleScope.launch {
            telnyxViewModel.uiState.collect { uiState ->
                when(uiState) {
                    is TelnyxSocketEvent.OnClientReady -> {
                        onIdle()
                    }
                    is TelnyxSocketEvent.OnClientError -> {
                        Toast.makeText(requireContext(), uiState.message, Toast.LENGTH_LONG).show()
                    }
                    is TelnyxSocketEvent.OnIncomingCall -> {
                        onCallIncoming(uiState.message.callId, uiState.message.callerIdNumber)
                    }
                    is TelnyxSocketEvent.OnCallAnswered -> {
                        onCallActive()
                    }
                    is TelnyxSocketEvent.OnCallEnded -> {
                        onIdle()
                    }
                    is TelnyxSocketEvent.OnRinging -> {
                        onCallActive()
                    }
                    is TelnyxSocketEvent.InitState -> {
                        findNavController().popBackStack()
                        cancel()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun onIdle() {
        binding.callIdleView.visibility = View.VISIBLE
        binding.callActiveView.visibility = View.INVISIBLE
        binding.callIncomingView.visibility = View.INVISIBLE
    }

    private fun onCallActive() {
        binding.callIdleView.visibility = View.INVISIBLE
        binding.callActiveView.visibility = View.VISIBLE
        binding.callIncomingView.visibility = View.INVISIBLE
    }

    private fun onCallIncoming(callId: UUID, callerIdNumber: String) {
        binding.callIdleView.visibility = View.INVISIBLE
        binding.callActiveView.visibility = View.INVISIBLE
        binding.callIncomingView.visibility = View.VISIBLE

        binding.callAnswer.setOnClickListener {
            telnyxViewModel.answerCall(requireContext(), callId, callerIdNumber)
            registerObservers()
        }

        binding.callReject.setOnClickListener {
            telnyxViewModel.rejectCall(requireContext(), callId)
            registerObservers()
        }
    }

    private fun registerObservers() {
        telnyxViewModel.currentCall?.getIsOnLoudSpeakerStatus()?.observe(viewLifecycleOwner) { loudSpeakerOn ->
            (binding.loudSpeaker as? MaterialButton)?.setIconResource(if (loudSpeakerOn) R.drawable.speaker_off_24 else R.drawable.speaker_24)
        }

        telnyxViewModel.currentCall?.getIsMuteStatus()?.observe(viewLifecycleOwner) { muteOn ->
            (binding.mute as? MaterialButton)?.setIconResource(if (muteOn) R.drawable.mute_24 else R.drawable.mute_off_24)
        }

        telnyxViewModel.currentCall?.getIsOnHoldStatus()?.observe(viewLifecycleOwner) { onHold ->
            (binding.hold as? MaterialButton)?.setIconResource(if (onHold) R.drawable.play_24 else R.drawable.pause_24)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
