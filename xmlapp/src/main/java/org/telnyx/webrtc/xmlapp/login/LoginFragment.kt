package org.telnyx.webrtc.xmlapp.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.telnyx.webrtc.common.TelnyxViewModel
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
                        profileId.text = it.sipUsername ?: it.sipToken ?: getString(R.string.no_profile_selected)
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
            telnyxViewModel.currentProfile.value?.let {
                telnyxViewModel.credentialLogin(this@LoginFragment.requireContext(),it,null)
            }
            //findNavController().navigate(R.id.action_loginFragment_to_loginBottomSheetFragment)

        }


    }
}

override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
}

}
