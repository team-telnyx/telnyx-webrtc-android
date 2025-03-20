package org.telnyx.webrtc.xml_app.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.FragmentLoginBinding

class LoginFragment : Fragment() {
    private var _binding: FragmentLoginBinding? = null
    private val binding get() = _binding!!

    private lateinit var loginBottomSheetFragment: LoginBottomSheetFragment
    private val telnyxViewModel: TelnyxViewModel by activityViewModels()


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
            lifecycleScope.launch {
                telnyxViewModel.currentProfile.collectLatest { profile ->
                    profile?.let {
                        profileId.text = it.callerIdName ?: getString(R.string.no_profile_selected)
                    }
                }
            }

            lifecycleScope.launch {
                telnyxViewModel.uiState.collect { uiState ->
                    when (uiState) {
                        is TelnyxSocketEvent.OnClientReady -> {
                            telnyxViewModel.currentProfile.value?.let { profile ->
                                profile.isUserLoggedIn = true
                                telnyxViewModel.setCurrentConfig(requireContext(), profile)
                            }
                            findNavController().navigate(R.id.action_loginFragment_to_homeFragment)
                            cancel()
                        }
                        is TelnyxSocketEvent.OnClientError -> {
                            Toast.makeText(requireContext(), uiState.message, Toast.LENGTH_LONG).show()
                        }
                        else -> {}
                    }
                }
            }
    }
}

private fun setupListeners() {
    binding.apply {
        // Switch profile button click
        switchProfile.setOnClickListener {
            if (!loginBottomSheetFragment.isAdded) {
                loginBottomSheetFragment.show(
                    requireActivity().supportFragmentManager,
                    "loginBottomSheetFragment"
                )
            }
        }

        // Connect button click
        connect.setOnClickListener {
            telnyxViewModel.currentProfile.value?.let { currentProfile ->
                if (currentProfile.sipToken?.isEmpty() == false)
                    telnyxViewModel.tokenLogin(this@LoginFragment.requireContext(), currentProfile,null)
                else
                    telnyxViewModel.credentialLogin(this@LoginFragment.requireContext(), currentProfile,null)
            }

        }


    }
}

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}

}
