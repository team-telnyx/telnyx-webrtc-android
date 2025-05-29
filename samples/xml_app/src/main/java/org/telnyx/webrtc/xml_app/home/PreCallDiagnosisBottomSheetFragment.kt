package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxPrecallDiagnosisState
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.PreCallDiagnosisMetrics
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.PrecallDiagnosisBottomSheetBinding
import java.util.Locale

class PreCallDiagnosisBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: PrecallDiagnosisBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    companion object {
        const val TAG = "PreCallDiagnosisBottomSheetFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PrecallDiagnosisBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener {
            dismiss()
        }

        observePreCallDiagnosisState()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.let {
            val bottomSheet =
                it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun observePreCallDiagnosisState() {
        lifecycleScope.launch {
            telnyxViewModel.precallDiagnosisState.collect { state ->
                when (state) {
                    is TelnyxPrecallDiagnosisState.PrecallDiagnosisStarted -> {
                        updateUIForStarted()
                    }
                    is TelnyxPrecallDiagnosisState.PrecallDiagnosisCompleted -> {
                        updateUIForCompleted(state.data)
                    }
                    is TelnyxPrecallDiagnosisState.PrecallDiagnosisFailed -> {
                        updateUIForFailed()
                    }
                    null -> {
                        // Initial state, do nothing
                    }
                }
            }
        }
    }

    private fun updateUIForStarted() {
        binding.diagnosisStatusText.text = getString(R.string.precall_diagnosis_processing)
        binding.diagnosisStatusText.visibility = View.VISIBLE
        binding.progressDiagnosisLoading.visibility = View.VISIBLE
        binding.diagnosisDetailsLayout.visibility = View.GONE
    }

    private fun updateUIForCompleted(metrics: PreCallDiagnosisMetrics) {
        binding.diagnosisStatusText.visibility = View.GONE
        binding.progressDiagnosisLoading.visibility = View.GONE
        binding.diagnosisDetailsLayout.visibility = View.VISIBLE

        // Update Network Quality section
        binding.textQualityValue.text = metrics.quality.name
        binding.textMosValue.text = String.format(Locale.US, "%.2f", metrics.mos)

        // Update Jitter section
        binding.textJitterMinValue.text = String.format(Locale.US, "%.2f ms", metrics.jitter.min * 1000)
        binding.textJitterMaxValue.text = String.format(Locale.US, "%.2f ms", metrics.jitter.max * 1000)
        binding.textJitterAvgValue.text = String.format(Locale.US, "%.2f ms", metrics.jitter.avg * 1000)

        // Update RTT section
        binding.textRttMinValue.text = String.format(Locale.US, "%.2f ms", metrics.rtt.min * 1000)
        binding.textRttMaxValue.text = String.format(Locale.US, "%.2f ms", metrics.rtt.max * 1000)
        binding.textRttAvgValue.text = String.format(Locale.US, "%.2f ms", metrics.rtt.avg * 1000)

        // Update Session Statistics section
        binding.textBytesSentValue.text = metrics.bytesSent.toString()
        binding.textBytesReceivedValue.text = metrics.bytesReceived.toString()
        binding.textPacketsSentValue.text = metrics.packetsSent.toString()
        binding.textPacketsReceivedValue.text = metrics.packetsReceived.toString()

        // Update ICE Candidates section
        binding.iceCandidatesContainer.removeAllViews()
        metrics.iceCandidates.forEach { candidate ->
            addIceCandidateRow(candidate)
        }
    }

    private fun updateUIForFailed() {
        binding.diagnosisStatusText.text = getString(R.string.precall_diagnosis_failed)
        binding.diagnosisStatusText.visibility = View.VISIBLE
        binding.progressDiagnosisLoading.visibility = View.GONE
        binding.diagnosisDetailsLayout.visibility = View.GONE
    }

    private fun addIceCandidateRow(candidate: com.telnyx.webrtc.sdk.stats.ICECandidate) {
        val row = layoutInflater.inflate(R.layout.ice_candidate_row, binding.iceCandidatesContainer, false)
        
        val candidateText = row.findViewById<TextView>(R.id.candidateText)
        candidateText.text = getString(R.string.precall_diagnosis_ice_candidate_label) + " ${candidate.id}, ${candidate.transportId}, ${candidate.protocol}, ${candidate.priority}, " +
                "${candidate.candidateType}, ${candidate.address}, ${candidate.port}"

        binding.iceCandidatesContainer.addView(row)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 
