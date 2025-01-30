package org.telnyx.webrtc.xmlapp.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.telnyx.webrtc.xmlapp.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupUI()
    }

    private fun setupUI() {
        binding.iconButton.setOnClickListener {
            // Handle call button click
        }

        binding.mute.setOnClickListener {
            // Handle mute button click
        }

        binding.endCall.setOnClickListener {
            // Handle end call button click
        }

        binding.loudSpeaker.setOnClickListener {
            // Handle speaker button click
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}

