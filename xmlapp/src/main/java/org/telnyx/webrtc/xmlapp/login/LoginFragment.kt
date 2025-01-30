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

    private lateinit var loginBottomSheetFragment: LoginBottomSheetFragment


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
        loginBottomSheetFragment = LoginBottomSheetFragment()
        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        // Initial view setup
        binding.apply {
            // Set initial profile ID
            profileId.text = "xd34343"
        }
    }

    private fun setupListeners() {
        binding.apply {
            // Switch profile button click
            outlinedButton.setOnClickListener {
                if (!loginBottomSheetFragment.isAdded) {
                    loginBottomSheetFragment.show(requireActivity().supportFragmentManager, "loginBottomSheetFragment")
                }
            }

            // Connect button click
            connect.setOnClickListener {
                findNavController().navigate(R.id.action_loginFragment_to_loginBottomSheetFragment)

            }


        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

}
