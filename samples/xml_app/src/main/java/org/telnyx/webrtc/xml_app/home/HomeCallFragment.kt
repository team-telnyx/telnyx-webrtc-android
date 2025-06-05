package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import org.telnyx.webrtc.xmlapp.databinding.CallQualitySummaryBinding
import org.telnyx.webrtc.xmlapp.databinding.FragmentHomeCallBinding
import org.telnyx.webrtc.xml_app.login.DialpadFragment
import java.util.*


class HomeCallFragment : Fragment() {

    private var _binding: FragmentHomeCallBinding? = null
    private val binding get() = _binding!!

    private var _callQualitySummaryBinding: CallQualitySummaryBinding? = null
    private val callQualitySummaryBinding get() = _callQualitySummaryBinding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private lateinit var dialpadFragment: DialpadFragment
    private lateinit var callQualityBottomSheetFragment: CallQualityBottomSheetFragment

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeCallBinding.inflate(inflater, container, false)
        _callQualitySummaryBinding = CallQualitySummaryBinding.bind(binding.callQualitySummary.root)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dialpadFragment = DialpadFragment()
        callQualityBottomSheetFragment = CallQualityBottomSheetFragment()

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

        // Setup the "View all call metrics" button
        callQualitySummaryBinding.viewAllMetricsButton.setOnClickListener {
            showCallQualityBottomSheet()
        }

        // Setup the "Call History" button
        binding.callHistoryButton.setOnClickListener {
            showCallHistoryBottomSheet()
        }
    }

    private fun showCallQualityBottomSheet() {
        if (!callQualityBottomSheetFragment.isAdded) {
            callQualityBottomSheetFragment.show(
                requireActivity().supportFragmentManager,
                CallQualityBottomSheetFragment.TAG
            )
        }
    }

    private fun showCallHistoryBottomSheet() {
        val callHistoryBottomSheet = CallHistoryBottomSheet.newInstance()
        callHistoryBottomSheet.onNumberSelected = { destinationNumber ->
            binding.callInput.setText(destinationNumber)
        }
        callHistoryBottomSheet.show(
            requireActivity().supportFragmentManager,
            CallHistoryBottomSheet.TAG
        )
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
                        val cause = uiState.message?.cause
                        if (cause != null) getString(R.string.done_with_cause, cause) else getString(R.string.call_state_ended)

                        if (telnyxViewModel.currentCall != null)
                            onCallActive()
                        else
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
        callQualitySummaryBinding.root.visibility = View.GONE
        if (callQualityBottomSheetFragment.isAdded) {
            callQualityBottomSheetFragment.dismiss()
        }
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
        callQualitySummaryBinding.root.visibility = View.GONE

        if (callQualityBottomSheetFragment.isAdded) {
            callQualityBottomSheetFragment.dismiss()
        }

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
        if (_callQualitySummaryBinding == null) return

        if (metrics == null) {
            callQualitySummaryBinding.root.visibility = View.GONE
            return
        }

        // Show the summary view
        callQualitySummaryBinding.root.visibility = View.VISIBLE

        if (metrics.quality == CallQuality.UNKNOWN) {
            // If quality is unknown, we could show a loading state in the summary
            // For now, we'll just show the "Unknown" quality
            updateQualitySummary(metrics.quality)
        } else {
            updateQualitySummary(metrics.quality)
        }
    }

    private fun updateQualitySummary(quality: CallQuality) {
        val (colorRes, text) = getQualityIndicatorData(quality)
        
        // Update the colored dot
        callQualitySummaryBinding.qualityDot.backgroundTintList = 
            ContextCompat.getColorStateList(requireContext(), colorRes)
        
        // Update the quality text
        callQualitySummaryBinding.qualityText.text = text
    }

    private fun getQualityIndicatorData(quality: CallQuality): Pair<Int, String> {
        val color = when (quality) {
            CallQuality.EXCELLENT -> R.color.quality_excellent
            CallQuality.GOOD -> R.color.quality_good
            CallQuality.FAIR -> R.color.quality_fair
            CallQuality.POOR -> R.color.quality_poor
            CallQuality.BAD -> R.color.quality_bad
            CallQuality.UNKNOWN -> R.color.quality_unknown
        }

        val text = when (quality) {
            CallQuality.EXCELLENT -> getString(R.string.call_quality_excellent)
            CallQuality.GOOD -> getString(R.string.call_quality_good)
            CallQuality.FAIR -> getString(R.string.call_quality_fair)
            CallQuality.POOR -> getString(R.string.call_quality_poor)
            CallQuality.BAD -> getString(R.string.call_quality_bad)
            CallQuality.UNKNOWN -> getString(R.string.call_quality_unknown)
        }

        return Pair(color, text)
    }

    private fun capitalizeFirstChar(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str.lowercase().replaceFirstChar { it.uppercase() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        _callQualitySummaryBinding = null
    }
}
