package org.telnyx.webrtc.xmlapp.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.FragmentLoginBinding

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

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        // Initial view setup
        binding.apply {
            // Set initial profile ID
            profileInfo.profileId.text = "xd34343"
        }
    }

    private fun setupListeners() {
        binding.apply {
            // Switch profile button click
            profileInfo.outlinedButton.setOnClickListener {
                findNavController().navigate(R.id.action_loginFragment_to_loginBottomSheetFragment)
            }

            // Connect button click
            connect.setOnClickListener {
                // TODO: Implement connection logic
            }

            // Session switch toggle
            sessionSwitch.getChildAt(0).setOnClickListener { switchView ->
                if (switchView is com.google.android.material.switchmaterial.SwitchMaterial) {
                    val textView = sessionSwitch.getChildAt(1) as android.widget.TextView
                    textView.text = if (switchView.isChecked) getString(R.string.on) else getString(R.string.off)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}