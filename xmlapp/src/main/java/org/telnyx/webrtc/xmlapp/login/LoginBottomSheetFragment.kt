package org.telnyx.webrtc.xmlapp.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.telnyx.webrtc.xmlapp.databinding.FragmentLoginBottomSheetBinding

class LoginBottomSheetFragment : BottomSheetDialogFragment() {
    private var _binding: FragmentLoginBottomSheetBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBottomSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        binding.apply {
            // Set up RecyclerView
            allProfiles.layoutManager = LinearLayoutManager(requireContext())
            // TODO: Set up adapter for profiles
        }
    }

    private fun setupListeners() {
        binding.apply {
            // Close button click
            headerInfo.getChildAt(1).setOnClickListener {
                dismiss()
            }

            // Add new profile button click
            outlinedButton.setOnClickListener {
                // TODO: Implement add new profile logic
            }

            // Confirm button click
            connect.setOnClickListener {
                // TODO: Implement profile selection confirmation
                dismiss()
            }

            // Cancel button click
            cancel.setOnClickListener {
                dismiss()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}