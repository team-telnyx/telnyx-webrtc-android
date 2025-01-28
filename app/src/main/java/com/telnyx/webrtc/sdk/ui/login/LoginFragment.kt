package com.telnyx.webrtc.sdk.ui.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.databinding.FragmentLoginBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LoginFragment : Fragment() {

    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.connect.setOnClickListener {
            findNavController().navigate(R.id.action_login_to_home)
        }

        binding.outlinedButton.setOnClickListener {
            // Handle profile switch
        }

        binding.sessionSwitch.setOnCheckedChangeListener { _, isChecked ->
            binding.loginTypeSwitch.text = if (isChecked) {
                getString(R.string.credential_login)
            } else {
                getString(R.string.token_login)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}