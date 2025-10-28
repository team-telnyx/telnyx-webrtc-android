package org.telnyx.webrtc.xml_app.home

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.SocketConnectionQuality
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.ConnectionDetailsBottomSheetBinding
import java.util.Locale

class ConnectionDetailsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: ConnectionDetailsBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    companion object {
        const val TAG = "ConnectionDetailsBottomSheetFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = ConnectionDetailsBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.closeButton.setOnClickListener {
            dismiss()
        }

        observeConnectionMetrics()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.let {
            val bottomSheet =
                it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED // Fullscreen
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun observeConnectionMetrics() {
        lifecycleScope.launch {
            telnyxViewModel.connectionMetrics.collect { metrics ->
                updateConnectionMetrics(metrics)
            }
        }
    }


    private fun updateConnectionMetrics(metrics: SocketConnectionMetrics?) {
        if (metrics == null) {
            binding.progressMetricsLoading.visibility = View.VISIBLE
            binding.metricsDetailsLayout.visibility = View.GONE
            binding.noMetricsMessage.visibility = View.VISIBLE
            return
        }

        binding.progressMetricsLoading.visibility = View.GONE
        binding.noMetricsMessage.visibility = View.GONE
        binding.metricsDetailsLayout.visibility = View.VISIBLE

        // Update quality indicator
        updateQualityIndicator(metrics.quality ?: SocketConnectionQuality.DISCONNECTED)

        // Clear and update metrics
        binding.metricsContainer.removeAllViews()

        // Add metrics using the same style as CallQualityBottomSheetFragment
        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_interval),
            metrics.intervalMs?.let { "${it} ms" } ?: getString(R.string.connection_metrics_not_available))

        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_average_interval),
            metrics.averageIntervalMs?.let { "${it} ms" } ?: getString(R.string.connection_metrics_not_available))

        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_jitter),
            metrics.jitterMs?.let { "${it} ms" } ?: getString(R.string.connection_metrics_not_available))

        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_min_interval),
            metrics.minIntervalMs?.let { "${it} ms" } ?: getString(R.string.connection_metrics_not_available))

        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_max_interval),
            metrics.maxIntervalMs?.let { "${it} ms" } ?: getString(R.string.connection_metrics_not_available))

        // Success rate with color coding
        val successRate = metrics.getSuccessRate()
        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_success_rate),
            String.format(Locale.US, "%.1f%%", successRate))

        // Ping statistics section
        addSectionHeader(binding.metricsContainer, getString(R.string.connection_metrics_ping_stats))

        addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_total_pings),
            metrics.totalPings.toString())

        if (metrics.missedPings > 0) {
            addMetricRow(binding.metricsContainer, getString(R.string.connection_metrics_missed_pings),
                metrics.missedPings.toString())
        }
    }

    private fun updateQualityIndicator(quality: SocketConnectionQuality) {
        val qualityColor = when (quality) {
            SocketConnectionQuality.DISCONNECTED -> Color.parseColor("#616161")  // Dark Gray
            SocketConnectionQuality.CALCULATING -> Color.parseColor("#9E9E9E")   // Gray
            SocketConnectionQuality.EXCELLENT -> Color.parseColor("#4CAF50")     // Green
            SocketConnectionQuality.GOOD -> Color.parseColor("#8BC34A")          // Light Green
            SocketConnectionQuality.FAIR -> Color.parseColor("#FFC107")          // Amber
            SocketConnectionQuality.POOR -> Color.parseColor("#F44336")          // Red
        }

        val qualityText = when (quality) {
            SocketConnectionQuality.DISCONNECTED -> getString(R.string.connection_quality_disconnected)
            SocketConnectionQuality.CALCULATING -> getString(R.string.connection_quality_calculating)
            SocketConnectionQuality.EXCELLENT -> getString(R.string.connection_quality_excellent)
            SocketConnectionQuality.GOOD -> getString(R.string.connection_quality_good)
            SocketConnectionQuality.FAIR -> getString(R.string.connection_quality_fair)
            SocketConnectionQuality.POOR -> getString(R.string.connection_quality_poor)
        }

        val drawable = ContextCompat.getDrawable(requireContext(), R.drawable.status_circle)?.mutate()
        drawable?.setTint(qualityColor)
        binding.qualityIndicatorDot.background = drawable
        binding.qualityText.text = qualityText
    }

    private fun addMetricRow(container: LinearLayout, label: String, value: String) {
        val inflater = LayoutInflater.from(requireContext())
        val rowView = inflater.inflate(R.layout.metric_row_item, container, false)

        rowView.findViewById<TextView>(R.id.metric_label).text = label
        rowView.findViewById<TextView>(R.id.metric_value).text = value

        container.addView(rowView)
    }

    private fun addSectionHeader(container: LinearLayout, title: String) {
        val inflater = LayoutInflater.from(requireContext())
        val headerView = inflater.inflate(R.layout.section_header_item, container, false)

        headerView.findViewById<TextView>(R.id.section_title).text = title

        container.addView(headerView)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}