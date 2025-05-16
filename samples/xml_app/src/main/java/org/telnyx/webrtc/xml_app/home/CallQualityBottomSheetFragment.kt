package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.CallQuality
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.CallQualityBottomSheetBinding
import java.util.Locale

class CallQualityBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: CallQualityBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()
    private var metrics: CallQualityMetrics? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = CallQualityBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener {
            dismiss()
        }

        // Get the current metrics from the ViewModel
        metrics = telnyxViewModel.callQualityMetrics.value
        updateUI(metrics)
    }

    private fun updateUI(metrics: CallQualityMetrics?) {
        if (metrics == null) {
            binding.progressQualityLoading.visibility = View.VISIBLE
            binding.qualityDetailsLayout.visibility = View.GONE
            return
        }

        binding.progressQualityLoading.visibility = View.GONE
        binding.qualityDetailsLayout.visibility = View.VISIBLE

        // Update basic metrics
        binding.textJitterValue.text = String.format(Locale.US, "%.2f ms", metrics.jitter * 1000)
        binding.textMosValue.text = String.format(Locale.US, "%.2f", metrics.mos)
        binding.textQualityValue.text = capitalizeFirstChar(metrics.quality.name)
        binding.textRttValue.text = String.format(Locale.US, "%.2f ms", metrics.rtt * 1000)

        // Clear and update inbound audio metrics
        binding.inboundAudioContainer.removeAllViews()
        metrics.inboundAudio?.forEach { (key, value) ->
            addMetricRow(binding.inboundAudioContainer, capitalizeFirstChar(key), value.toString())
        }

        // Clear and update outbound audio metrics
        binding.outboundAudioContainer.removeAllViews()
        metrics.outboundAudio?.forEach { (key, value) ->
            addMetricRow(binding.outboundAudioContainer, capitalizeFirstChar(key), value.toString())
        }

        // In a real implementation, you would update the waveform views here
        // For now, we'll just use placeholder views
    }

    private fun addMetricRow(container: LinearLayout, label: String, value: String) {
        val inflater = LayoutInflater.from(requireContext())
        val rowView = inflater.inflate(R.layout.metric_row_item, container, false)
        
        rowView.findViewById<TextView>(R.id.metric_label).text = label
        rowView.findViewById<TextView>(R.id.metric_value).text = value
        
        container.addView(rowView)
    }

    private fun capitalizeFirstChar(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return str.lowercase().replaceFirstChar { it.uppercase() }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CallQualityBottomSheetFragment"
    }
}