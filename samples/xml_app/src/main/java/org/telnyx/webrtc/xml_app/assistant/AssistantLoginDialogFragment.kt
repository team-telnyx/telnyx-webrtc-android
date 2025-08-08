package org.telnyx.webrtc.xml_app.assistant

import android.app.Dialog
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R

class AssistantLoginDialogFragment : DialogFragment() {
    
    private val telnyxViewModel: TelnyxViewModel by activityViewModels()
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_assistant_login, null)
        
        val etTargetId = view.findViewById<TextInputEditText>(R.id.etTargetId)
        val btnLogin = view.findViewById<MaterialButton>(R.id.btnLogin)
        val btnCancel = view.findViewById<MaterialButton>(R.id.btnCancel)
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
        
        btnLogin.setOnClickListener {
            val targetId = etTargetId.text?.toString()?.trim()
            
            if (targetId.isNullOrEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.assistant_error_empty_target_id),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            
            // Perform anonymous login for assistant
            lifecycleScope.launch {
                try {
                    telnyxViewModel.anonymousLogin(requireContext(), targetId)
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.assistant_connected),
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                } catch (e: Exception) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.assistant_error_connection_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
        
        btnCancel.setOnClickListener {
            dialog.dismiss()
        }
        
        return dialog
    }
    
    companion object {
        const val TAG = "AssistantLoginDialog"
        
        fun newInstance(): AssistantLoginDialogFragment {
            return AssistantLoginDialogFragment()
        }
    }
}