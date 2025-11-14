package org.telnyx.webrtc.xml_app.home

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.AudioConstraints
import org.telnyx.webrtc.xmlapp.R

/**
 * Dialog for configuring audio processing constraints.
 *
 * @param telnyxViewModel The ViewModel instance to store constraints
 */
class AudioConstraintsDialogFragment(
    private val telnyxViewModel: TelnyxViewModel
) : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_audio_constraints, null)

        // Get current constraints or use defaults
        val currentConstraints = telnyxViewModel.getAudioConstraints() ?: AudioConstraints()

        // Initialize checkboxes with current values
        val cbEchoCancellation = view.findViewById<CheckBox>(R.id.cbEchoCancellation)
        val cbNoiseSuppression = view.findViewById<CheckBox>(R.id.cbNoiseSuppression)
        val cbAutoGainControl = view.findViewById<CheckBox>(R.id.cbAutoGainControl)

        cbEchoCancellation.isChecked = currentConstraints.echoCancellation
        cbNoiseSuppression.isChecked = currentConstraints.noiseSuppression
        cbAutoGainControl.isChecked = currentConstraints.autoGainControl

        // Setup buttons
        val btnCancel = view.findViewById<Button>(R.id.btnCancel)
        val btnSave = view.findViewById<Button>(R.id.btnSave)

        btnCancel.setOnClickListener {
            dismiss()
        }

        btnSave.setOnClickListener {
            val newConstraints = AudioConstraints(
                echoCancellation = cbEchoCancellation.isChecked,
                noiseSuppression = cbNoiseSuppression.isChecked,
                autoGainControl = cbAutoGainControl.isChecked
            )

            telnyxViewModel.setAudioConstraints(newConstraints)

            Toast.makeText(
                requireContext(),
                R.string.audio_constraints_saved,
                Toast.LENGTH_SHORT
            ).show()

            dismiss()
        }

        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }
}
