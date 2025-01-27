package com.telnyx.webrtc.sdk.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.databinding.FragmentHomeBinding
import com.telnyx.webrtc.sdk.ui.base.BaseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : BaseFragment<FragmentHomeBinding>() {
    private val viewModel: MainViewModel by viewModels()

    override fun getViewBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeBinding {
        return FragmentHomeBinding.inflate(inflater, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupViews()
        observeViewModel()
    }

    private fun setupViews() {
        binding.makeCallButton.setOnClickListener {
            val destination = binding.destinationInput.text.toString()
            if (destination.isNotEmpty()) {
                findNavController().navigate(R.id.action_homeFragment_to_callInstanceFragment)
                viewModel.initiateCall(destination)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.callState.observe(viewLifecycleOwner) { state ->
            binding.callStateText.text = state
        }
    }
}