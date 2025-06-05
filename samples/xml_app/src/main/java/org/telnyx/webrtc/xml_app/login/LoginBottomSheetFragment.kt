package org.telnyx.webrtc.xml_app.login

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
import android.view.animation.AnimationUtils
import org.telnyx.webrtc.xml_app.MainActivity

class LoginBottomSheetFragment : BottomSheetDialogFragment() {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private var _binding: FragmentLoginBottomSheetBinding? = null
    private val binding get() = _binding!!

    private var _credentialsBinding: CredentialsLayoutBinding? = null
    private val credentialsBinding get() = _credentialsBinding!!

    private var isCredentialLayoutVisible = false

    private lateinit var adapter: ProfileListAdapter

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
            val isDebug = debugSwitch.isChecked

            if (!validateProfile()) return

            val newProfile = Profile(
                sipToken = sipToken,
                sipUsername = sipUser,
                sipPass = sipPass,
                callerIdName = sipCallerIdName,
                callerIdNumber = sipCallerIdNumber,
                isDebug = isDebug
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
            debugSwitch.isChecked = false
        }
    }

    private fun validateProfile(): Boolean {
        var isValid = true
        credentialsBinding.apply {
            val sipToken = tokenTextField.text.toString()
            val sipUser = usernameTextField.text.toString()
            val sipPass = passwordTextField.text.toString()

            if (credentialsBinding.sessionSwitch.checkedButtonId == R.id.tokenLogin) {
                if (sipToken.isEmpty()) {
                    tokenTextField.error = getString(R.string.error_empty_field)
                    isValid = false
                }
            } else {
                if (sipUser.isEmpty()) {
                    usernameTextField.error = getString(R.string.error_empty_field)
                    isValid = false
                }
                if (sipPass.isEmpty()) {
                    passwordTextField.error = getString(R.string.error_empty_field)
                    isValid = false
                }
            }
        }
        return isValid
    }

    private fun setupRecyclerView() {
        binding.allProfiles.layoutManager = LinearLayoutManager(requireContext())
        adapter = ProfileListAdapter() { profile,action ->
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
                    adapter.setSelectedProfile(profile)
                }
            }
        }
        binding.allProfiles.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    telnyxViewModel.profileList.collectLatest { profiles ->
                        adapter.submitList(profiles) // Submit the new list to the adapter
                    }
                }

                launch {
                    telnyxViewModel.currentProfile.collectLatest { selectedProfile ->
                        adapter.setSelectedProfile(selectedProfile)
                    }
                }
            }
        }
    }

    private fun setupListeners() {
        telnyxViewModel.setupProfileList(requireContext())
        binding.apply {
            headerInfo.getChildAt(1).setOnClickListener { dismiss() }
            addNewProfile.setOnClickListener { toggleCredentialLayout(!isCredentialLayoutVisible) }
            credentialsBinding.cancelButton.setOnClickListener { toggleCredentialLayout(false) }

            credentialsBinding.sessionSwitch.addOnButtonCheckedListener { group, checkedId, isChecked ->
                if (isChecked) {
                    when (checkedId) {
                        R.id.credentialLogin -> {
                            (activity as? MainActivity)?.highlightButton(credentialsBinding.credentialLogin)
                            (activity as? MainActivity)?.resetButton(credentialsBinding.tokenLogin)
                            toggleLoginFields(false)
                        }
                        R.id.tokenLogin -> {
                            (activity as? MainActivity)?.highlightButton(credentialsBinding.tokenLogin)
                            (activity as? MainActivity)?.resetButton(credentialsBinding.credentialLogin)
                            toggleLoginFields(true)
                        }
                    }
                }
            }

            credentialsBinding.confirmButton.setOnClickListener { saveProfile() }
            profileCancelButton.setOnClickListener {
                adapter.setSelectedProfile(null)
                dismiss()
            }
            profileConfirmButton.setOnClickListener {
                adapter.selectedProfile?.let {
                    telnyxViewModel.setCurrentConfig(requireContext(), it)
                }
                dismiss()
            }
        }
    }

    private fun toggleCredentialLayout(show: Boolean) {
        isCredentialLayoutVisible = show
        binding.allProfiles.visibility = if (show) View.GONE else View.VISIBLE
        binding.profileCancelButton.visibility = if (show) View.GONE else View.VISIBLE
        binding.profileConfirmButton.visibility = if (show) View.GONE else View.VISIBLE
        
        val credentialsLayout = binding.credentialGroup.credentialsLayout
        if (show) {
            credentialsLayout.visibility = View.VISIBLE
            val slideDown = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_down)
            credentialsLayout.startAnimation(slideDown)
        } else {
            val slideUp = AnimationUtils.loadAnimation(requireContext(), R.anim.slide_up)
            slideUp.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
                override fun onAnimationStart(animation: android.view.animation.Animation?) {}
                override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
                override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                    credentialsLayout.visibility = View.GONE
                }
            })
            credentialsLayout.startAnimation(slideUp)
        }
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
