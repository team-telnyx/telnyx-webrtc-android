package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.databinding.CallHistoryBottomSheetBinding

class CallHistoryBottomSheet : BottomSheetDialogFragment() {

    private var _binding: CallHistoryBottomSheetBinding? = null
    private val binding get() = _binding!!

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()
    private lateinit var callHistoryAdapter: CallHistoryAdapter

    var onNumberSelected: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = CallHistoryBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupClickListeners()
        observeCallHistory()
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.let {
            val bottomSheet =
                it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_HALF_EXPANDED // Fullscreen
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    private fun setupRecyclerView() {
        callHistoryAdapter = CallHistoryAdapter { callHistoryItem ->
            // Handle call button click
            onNumberSelected?.invoke(callHistoryItem.destinationNumber)
            telnyxViewModel.sendInvite(
                requireContext(),
                callHistoryItem.destinationNumber,
                false
            )
            dismiss()
        }

        binding.callHistoryRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = callHistoryAdapter
        }
    }

    private fun setupClickListeners() {
        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }

    private fun observeCallHistory() {
        lifecycleScope.launch {
            telnyxViewModel.callHistoryList.collect { callHistory ->
                if (callHistory.isEmpty()) {
                    binding.callHistoryRecyclerView.visibility = View.GONE
                    binding.emptyStateText.visibility = View.VISIBLE
                } else {
                    binding.callHistoryRecyclerView.visibility = View.VISIBLE
                    binding.emptyStateText.visibility = View.GONE
                    callHistoryAdapter.submitList(callHistory)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "CallHistoryBottomSheet"

        fun newInstance(): CallHistoryBottomSheet {
            return CallHistoryBottomSheet()
        }
    }
}