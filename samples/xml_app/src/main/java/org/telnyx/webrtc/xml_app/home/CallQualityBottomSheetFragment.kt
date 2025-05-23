package org.telnyx.webrtc.xml_app.home

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.stats.CallQualityMetrics
import com.telnyx.webrtc.sdk.utilities.Logger
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xml_app.utils.dpToPx
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.CallQualityBottomSheetBinding
import java.util.Locale

class CallQualityBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: CallQualityBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    companion object {
        const val TAG = "CallQualityBottomSheetFragment"
    }

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

        observeMetrics()
        observeWaveforms()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.let {
            val bottomSheet =
                it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                //behavior.isDraggable = false
                behavior.state = BottomSheetBehavior.STATE_EXPANDED // Fullscreen
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun observeMetrics() {
        lifecycleScope.launch {
            telnyxViewModel.callQualityMetrics.collect { metrics ->
                updateUI(metrics)
            }
        }
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
    }

    private fun observeWaveforms() {
        lifecycleScope.launch {
            telnyxViewModel.inboundAudioLevels.collect { levels ->
                renderOrUpdateWaveform(binding.inboundAudioWaveform, levels)
            }
        }

        lifecycleScope.launch {
            telnyxViewModel.outboundAudioLevels.collect { levels ->
                renderOrUpdateWaveform(binding.outboundAudioWaveform, levels)
            }
        }
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

    private fun renderOrUpdateWaveform(
        container: LinearLayout,
        audioLevels: List<Float>,
        barColor: Int = Color.BLACK,
        minBarHeight: Float = 2f,
        maxBarHeight: Float = 50f
    ) {
        val barMargin = 2.dpToPx(container.context)
        val barWidth = 2.dpToPx(container.context)

        container.post {
            val containerWidth = container.width
            val maxElements = containerWidth / (barWidth + barMargin)

            val levels = buildList {
                val lastItems = audioLevels.takeLast(maxElements)
                addAll(lastItems)
                repeat(maxElements - lastItems.size) {
                    add(0f)
                }
            }

            if (container.childCount != levels.size) {
                container.removeAllViews()
                levels.forEach { _ ->
                    val barView = View(container.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            barWidth,
                            minBarHeight.dpToPx(context)
                        ).apply {
                            leftMargin = barMargin
                            rightMargin = barMargin
                        }

                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 4.dpToPx(context).toFloat()
                            setColor(barColor)
                        }
                    }
                    container.addView(barView)
                }
            }

            levels.forEachIndexed { index, level ->
                val clamped = level.coerceIn(0f, 1f)
                val barHeight = minBarHeight + (clamped * (maxBarHeight - minBarHeight))

                val barView = container.getChildAt(index)
                barView?.layoutParams?.height = barHeight.dpToPx(container.context)
                barView?.requestLayout()
            }
        }
    }
}