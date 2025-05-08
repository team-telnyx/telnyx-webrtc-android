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
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.button.MaterialButton
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xml_app.MainActivity
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.CallQualityDisplayBinding
import org.telnyx.webrtc.xmlapp.databinding.FragmentHomeCallBinding
import org.telnyx.webrtc.xml_app.login.DialpadFragment
import java.util.*


class HomeCallFragment : Fragment() {

    private var _binding: FragmentHomeCallBinding? = null
    private val binding get() = _binding!!

    private var _callQualityBinding: CallQualityDisplayBinding? = null
    private val callQualityBinding get() = _callQualityBinding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private lateinit var dialpadFragment: DialpadFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeCallBinding.inflate(inflater, container, false)
        _callQualityBinding = CallQualityDisplayBinding.bind(binding.callQualityDisplay.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialpadFragment = DialpadFragment()

        setupUI()
        bindEvents()
        observeCallQuality()
    }

    private fun setupUI() {
        // Setup call type toggle
        binding.callTypeSwitch.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.phoneNumberToggle -> {
                        binding.callInput.inputType = android.text.InputType.TYPE_CLASS_PHONE
                        (activity as? MainActivity)?.highlightButton(binding.phoneNumberToggle)
                        (activity as? MainActivity)?.resetButton(binding.sipAddressToggle)
                    }
                    R.id.sipAddressToggle -> {
                        binding.callInput.inputType = android.text.InputType.TYPE_CLASS_TEXT
                        (activity as? MainActivity)?.highlightButton(binding.sipAddressToggle)
                        (activity as? MainActivity)?.resetButton(binding.phoneNumberToggle)
                    }
                }
            }
        }

        binding.call.setOnClickListener {
            binding.callInput.text?.let { editable ->
                if (editable.isNotEmpty()) {
                    telnyxViewModel.sendInvite(
                        this@HomeCallFragment.requireContext(),
                        editable.trim().toString(),
                        true // Enable call quality stats
                    )
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
                when (uiState) {
                    is TelnyxSocketEvent.OnClientReady -> {
                        onIdle()
                    }

                    is TelnyxSocketEvent.OnIncomingCall -> {
                        onCallIncoming(uiState.message.callId, uiState.message.callerIdNumber)
                    }

                    is TelnyxSocketEvent.OnCallAnswered -> {
                        onCallActive()
                        registerObservers() // Register observers when call is answered
                    }

                    is TelnyxSocketEvent.OnCallEnded -> {
                        onIdle()
                    }

                    is TelnyxSocketEvent.OnRinging -> {
                        onCallActive()
                        registerObservers() // Register observers when call is ringing (outgoing)
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
        binding.callActiveView.visibility = View.GONE
        binding.callIncomingView.visibility = View.GONE
        binding.callTypeSwitch.visibility = View.VISIBLE
        binding.destinationInfo.visibility = View.VISIBLE
        binding.callInput.isEnabled = true
        callQualityBinding.root.visibility = View.GONE // Hide quality display on idle
    }

    private fun onCallActive() {
        binding.callIdleView.visibility = View.GONE
        binding.callActiveView.visibility = View.VISIBLE
        binding.callIncomingView.visibility = View.INVISIBLE
        // Quality display visibility is handled by observeCallQuality based on metrics
        binding.callTypeSwitch.visibility = View.GONE
        binding.destinationInfo.visibility = View.VISIBLE
        binding.callInput.isEnabled = false
    }

    private fun onCallIncoming(callId: UUID, callerIdNumber: String) {
        binding.callIdleView.visibility = View.GONE
        binding.callActiveView.visibility = View.GONE
        binding.callIncomingView.visibility = View.VISIBLE
        binding.callTypeSwitch.visibility = View.GONE
        binding.destinationInfo.visibility = View.GONE
        callQualityBinding.root.visibility = View.GONE // Hide quality display for incoming call

        binding.callAnswer.setOnClickListener {
            telnyxViewModel.answerCall(requireContext(), callId, callerIdNumber, true) // Enable call quality stats
            // Observers will be registered in OnCallAnswered
            binding.callInput.setText(callerIdNumber)
        }

        binding.callReject.setOnClickListener {
            telnyxViewModel.rejectCall(requireContext(), callId)
            // No observers needed after rejection
        }
    }

    private fun registerObservers() {
        telnyxViewModel.currentCall?.getIsOnLoudSpeakerStatus()
            ?.observe(viewLifecycleOwner) { loudSpeakerOn ->
                (binding.loudSpeaker as? MaterialButton)?.setIconResource(if (loudSpeakerOn) R.drawable.speaker_24 else R.drawable.speaker_off_24)
            }

        telnyxViewModel.currentCall?.getIsMuteStatus()?.observe(viewLifecycleOwner) { muteOn ->
            (binding.mute as? MaterialButton)?.setIconResource(if (muteOn) R.drawable.mute_off_24 else R.drawable.mute_24)
        }

        telnyxViewModel.currentCall?.getIsOnHoldStatus()?.observe(viewLifecycleOwner) { onHold ->
            (binding.hold as? MaterialButton)?.setIconResource(if (onHold) R.drawable.play_24 else R.drawable.pause_24)
        }
    }

    private fun observeCallQuality() {
        viewLifecycleOwner.lifecycleScope.launch {
            telnyxViewModel.callQualityMetrics.collect { metrics ->
                updateCallQualityUI(metrics)
            }
        }
    }

    private fun updateCallQualityUI(metrics: CallQualityMetrics?) {
        if (_callQualityBinding == null) return

        if (metrics == null) {
            callQualityBinding.root.visibility = View.GONE
            return
        }

        callQualityBinding.root.visibility = View.VISIBLE

        if (metrics.quality == CallQuality.UNKNOWN) {
            callQualityBinding.progressQualityLoading.visibility = View.VISIBLE
            callQualityBinding.qualityDetailsLayout.visibility = View.GONE
        } else {
            callQualityBinding.progressQualityLoading.visibility = View.GONE
            callQualityBinding.qualityDetailsLayout.visibility = View.VISIBLE

            val (colorRes, text) = getQualityIndicatorData(metrics.quality)
            callQualityBinding.qualityIndicatorColor.backgroundTintList = ContextCompat.getColorStateList(requireContext(), colorRes)
            callQualityBinding.qualityIndicatorText.text = text

            callQualityBinding.textMosValue.text = String.format(Locale.US, "%.2f", metrics.mos)
            callQualityBinding.textJitterValue.text = String.format(Locale.US, "%.2f ms", metrics.jitter * 1000)
            callQualityBinding.textRttValue.text = String.format(Locale.US, "%.2f ms", metrics.rtt * 1000)
            callQualityBinding.textInboundLevelValue.text = String.format(Locale.US, "%.2f", metrics.inboundAudioLevel)
            callQualityBinding.textOutboundLevelValue.text = String.format(Locale.US, "%.2f", metrics.outboundAudioLevel)
        }
    }

    private fun getQualityIndicatorData(quality: CallQuality): Pair<Int, String> {
        return when (quality) {
            CallQuality.EXCELLENT -> R.color.quality_excellent to "Excellent"
            CallQuality.GOOD -> R.color.quality_good to "Good"
            CallQuality.FAIR -> R.color.quality_fair to "Fair"
            CallQuality.POOR -> R.color.quality_poor to "Poor"
            CallQuality.BAD -> R.color.quality_bad to "Bad"
            CallQuality.UNKNOWN -> R.color.quality_unknown to "Unknown"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        _callQualityBinding = null
    }

}
