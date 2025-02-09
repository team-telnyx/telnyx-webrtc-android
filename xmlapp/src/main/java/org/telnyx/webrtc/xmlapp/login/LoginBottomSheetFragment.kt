package org.telnyx.webrtc.xmlapp.login

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.model.Profile
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.CredentialsLayoutBinding
import org.telnyx.webrtc.xmlapp.databinding.FragmentLoginBottomSheetBinding

class LoginBottomSheetFragment : BottomSheetDialogFragment() {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private var _binding: FragmentLoginBottomSheetBinding? = null
    private val binding get() = _binding!!

    private var _credentialsBinding: CredentialsLayoutBinding? = null
    private val credentialsBinding get() = _credentialsBinding!!

    private var isCredentialLayoutVisible = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLoginBottomSheetBinding.inflate(inflater, container, false)

        _credentialsBinding = CredentialsLayoutBinding.bind(binding.credentialGroup.root)

        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog = dialog as? BottomSheetDialog
        dialog?.let {
            val bottomSheet =
                it.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)

            bottomSheet?.let { sheet ->
                val behavior = BottomSheetBehavior.from(sheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED // Fullscreen
                sheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
    }


    private fun saveProfile() {

        credentialsBinding.apply {
            val sipToken = tokenTextField.text.toString()
            val sipUser = usernameTextField.text.toString()
            val sipPass = passwordTextField.text.toString()
            val sipCallerIdName = callerIdNameTextField.text.toString()
            val sipCallerIdNumber = callerIdNumberTextField.text.toString()

            if (!validateProfile()) return

            val newProfile = Profile(
                sipToken = sipToken,
                sipUsername = sipUser,
                sipPass = sipPass,
                callerIdName = sipCallerIdName,
                callerIdNumber = sipCallerIdNumber
            )
            telnyxViewModel.addProfile(this@LoginBottomSheetFragment.requireContext(), newProfile)
            resetFields()
            toggleCredentialLayout(false)
        }
    }

    private fun resetFields() {
        credentialsBinding.apply {
            tokenTextField.text?.clear()
            usernameTextField.text?.clear()
            passwordTextField.text?.clear()
            callerIdNameTextField.text?.clear()
            callerIdNumberTextField.text?.clear()
        }
    }

    private fun validateProfile(): Boolean {
        credentialsBinding.apply {
            val sipToken = tokenTextField.text.toString()
            val sipUser = usernameTextField.text.toString()
            val sipPass = passwordTextField.text.toString()

            if (sessionSwitch.isChecked) {
                if (sipToken.isEmpty()) {
                    tokenTextField.error = getString(R.string.error_empty_field)
                    return false
                }
            } else {
                if (sipUser.isEmpty()) {
                    usernameTextField.error = getString(R.string.error_empty_field)
                    return false
                }
                if (sipPass.isEmpty()) {
                    passwordTextField.error = getString(R.string.error_empty_field)
                    return false
                }
            }
        }
        return true
    }

    private fun setupRecyclerView() {
        binding.allProfiles.layoutManager = LinearLayoutManager(requireContext())
        val adapter = ProfileListAdapter() { profile,action ->
            when(action) {
                ProfileAction.DELETE_PROFILE -> {
                    telnyxViewModel.deleteProfile(requireContext(), profile)

                }
                ProfileAction.EDIT_PROFILE -> {
                    toggleCredentialLayout(true)
                    toggleLoginFields(profile.sipToken?.isNotEmpty() ?: false)
                    credentialsBinding.apply {
                        tokenTextField.setText(profile.sipToken)
                        usernameTextField.setText(profile.sipUsername)
                        passwordTextField.setText(profile.sipPass)
                        callerIdNameTextField.setText(profile.callerIdName)
                        callerIdNumberTextField.setText(profile.callerIdNumber)
                    }
                }
                ProfileAction.SELECT_PROFILE -> {
                    telnyxViewModel.setCurrentConfig(profile)
                    dismiss()
                }
            }
            telnyxViewModel.setCurrentConfig(profile)
        }
        binding.allProfiles.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                telnyxViewModel.profileList.collectLatest { profiles ->
                    adapter.submitList(profiles) // Submit the new list to the adapter
                }
            }
        }
    }

    private fun setupListeners() {
        telnyxViewModel.setupProfileList(requireContext())
        binding.apply {
            headerInfo.getChildAt(1).setOnClickListener { dismiss() }
            addNewProfile.setOnClickListener { toggleCredentialLayout(true) }
            credentialsBinding.cancelButton.setOnClickListener { toggleCredentialLayout(false) }

            credentialsBinding.sessionSwitch.setOnCheckedChangeListener { _, isChecked ->
                toggleLoginFields(isChecked)
            }

            credentialsBinding.confirmButton.setOnClickListener { saveProfile() }
        }
    }

    private fun toggleCredentialLayout(show: Boolean) {
        isCredentialLayoutVisible = show
        binding.addNewProfile.visibility = if (show) View.GONE else View.VISIBLE
        binding.credentialGroup.credentialsLayout.visibility = if (show) View.VISIBLE else View.GONE
        binding.allProfiles.visibility = if (show) View.GONE else View.VISIBLE
    }

    private fun toggleLoginFields(useToken: Boolean) {
        credentialsBinding.apply {
            usernameTextFieldLayout.visibility = if (useToken) View.GONE else View.VISIBLE
            passwordTextFieldLayout.visibility = if (useToken) View.GONE else View.VISIBLE
            tokenTextFieldLayout.visibility = if (useToken) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        _credentialsBinding = null
    }
}
