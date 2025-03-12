@file:Suppress("MagicNumber")

package org.telnyx.webrtc.xml_app.login

import android.media.AudioManager
import android.media.ToneGenerator
import android.media.ToneGenerator.TONE_DTMF_0
import android.media.ToneGenerator.TONE_DTMF_1
import android.media.ToneGenerator.TONE_DTMF_2
import android.media.ToneGenerator.TONE_DTMF_3
import android.media.ToneGenerator.TONE_DTMF_4
import android.media.ToneGenerator.TONE_DTMF_5
import android.media.ToneGenerator.TONE_DTMF_6
import android.media.ToneGenerator.TONE_DTMF_7
import android.media.ToneGenerator.TONE_DTMF_8
import android.media.ToneGenerator.TONE_DTMF_9
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.davidmiguel.numberkeyboard.NumberKeyboardListener
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import org.telnyx.webrtc.xmlapp.databinding.FragmentDialpadBinding

class DialpadFragment: BottomSheetDialogFragment(), NumberKeyboardListener {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    private var _binding: FragmentDialpadBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialpadBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.dialpad.setListener(this)

        binding.close.setOnClickListener {
            binding.dialpadOutput.setText("")
            dismiss()
        }
    }

    override fun onLeftAuxButtonClicked() {
        //NOOP
    }

    override fun onNumberClicked(number: Int) {
        telnyxViewModel.dtmfPressed(number.toString())
        binding.dialpadOutput.text?.append(number.toString())

        when (number) {
            0 -> {
                toneGenerator.startTone(TONE_DTMF_0, 500)
            }
            1 -> {
                toneGenerator.startTone(TONE_DTMF_1, 500)
            }
            2 -> {
                toneGenerator.startTone(TONE_DTMF_2, 500)
            }
            3 -> {
                toneGenerator.startTone(TONE_DTMF_3, 500)
            }
            4 -> {
                toneGenerator.startTone(TONE_DTMF_4, 500)
            }
            5 -> {
                toneGenerator.startTone(TONE_DTMF_5, 500)
            }
            6 -> {
                toneGenerator.startTone(TONE_DTMF_6, 500)
            }
            7 -> {
                toneGenerator.startTone(TONE_DTMF_7, 500)
            }
            8 -> {
                toneGenerator.startTone(TONE_DTMF_8, 500)
            }
            9 -> {
                toneGenerator.startTone(TONE_DTMF_9, 500)
            }
        }
    }

    override fun onRightAuxButtonClicked() {
        val text = binding.dialpadOutput.text?.toString()
        if (!text.isNullOrEmpty()) {
            binding.dialpadOutput.setText(text.dropLast(1))
            binding.dialpadOutput.setSelection(binding.dialpadOutput.text!!.length)
        }
    }
}
