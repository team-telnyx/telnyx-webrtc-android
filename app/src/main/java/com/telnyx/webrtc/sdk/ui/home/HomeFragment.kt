package com.telnyx.webrtc.sdk.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.databinding.FragmentHomeBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.iconButton.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_callInstance)
        }

        binding.mute.setOnClickListener {
            // Handle mute
        }

        binding.endCall.setOnClickListener {
            // Handle end call
        }

        binding.loudSpeaker.setOnClickListener {
            // Handle loud speaker
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}