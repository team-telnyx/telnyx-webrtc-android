package org.telnyx.webrtc.xmlapp.home

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.databinding.FragmentHomeCallBinding
import java.util.*


class HomeCallFragment : Fragment() {

    private var _binding: FragmentHomeCallBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeCallBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

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
            // Handle mute button click
        }

        binding.endCall.setOnClickListener {
            telnyxViewModel.endCall(this@HomeCallFragment.requireContext())
        }

        binding.loudSpeaker.setOnClickListener {
            // Handle speaker button click
        }

        binding.callReject.setOnClickListener {
            telnyxViewModel.endCall(this@HomeCallFragment.requireContext())
        }

        binding.disconnect.setOnClickListener {
            telnyxViewModel.disconnect(this@HomeCallFragment.requireContext())
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
                        if (telnyxViewModel.currentCall != null)
                            onCallActive()
                        else
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
            onCallActive()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
