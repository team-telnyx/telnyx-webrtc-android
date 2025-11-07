package org.telnyx.webrtc.xml_app.assistant

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.TranscriptItem
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xml_app.utils.Utils
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.DialogAssistantTranscriptBinding
import org.telnyx.webrtc.xmlapp.databinding.FragmentLoginBottomSheetBinding
import java.util.*

class AssistantTranscriptDialogFragment : BottomSheetDialogFragment() {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private var _binding: DialogAssistantTranscriptBinding? = null
    private val binding get() = _binding!!

    private lateinit var transcriptAdapter: TranscriptAdapter

    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            handleImageSelection(selectedUri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogAssistantTranscriptBinding.inflate(inflater, container, false)
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
        observeTranscript()
    }
    
    private fun setupRecyclerView() {
        transcriptAdapter = TranscriptAdapter()
        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = transcriptAdapter
        }
    }
    
    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            val message = binding.etMessage.text?.toString()?.trim()
            if (!message.isNullOrEmpty()) {
                sendMessage(message)
                binding.etMessage.text?.clear()
            }
        }

        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }
    
    private fun sendMessage(message: String) {
        // Add user message to transcript
        val userTranscript = TranscriptItem(
            id = UUID.randomUUID().toString(),
            role = TranscriptItem.ROLE_USER,
            content = message,
            timestamp = Date()
        )
        transcriptAdapter.addTranscriptItem(userTranscript)
        scrollToBottom()
        
        // Send message through TelnyxClient (this would be implemented based on the actual API)
        // For now, we'll simulate an assistant response
        lifecycleScope.launch {
            // Simulate assistant response after a delay
            kotlinx.coroutines.delay(1000)
            val assistantResponse = TranscriptItem(
                id = UUID.randomUUID().toString(),
                role = TranscriptItem.ROLE_ASSISTANT,
                content = "I received your message: \"$message\". How can I help you further?",
                timestamp = Date()
            )
            transcriptAdapter.addTranscriptItem(assistantResponse)
            scrollToBottom()
        }
    }
    
    private fun observeTranscript() {
        lifecycleScope.launch {
            telnyxViewModel.transcriptMessages?.collect { transcriptItems ->
                transcriptAdapter.updateTranscript(transcriptItems)
                scrollToBottom()
            }
        }
    }
    
    private fun scrollToBottom() {
        if (transcriptAdapter.itemCount > 0) {
            binding.rvTranscript.scrollToPosition(transcriptAdapter.itemCount - 1)
        }
    }

    private fun handleImageSelection(uri: Uri) {
        // Convert URI to base64
        val base64Image = Utils.uriToBase64(requireContext(), uri)

        base64Image?.let {
            // Send the image to AI assistant
            telnyxViewModel.sendAIAssistantMessage(
                requireContext(),
                message = "",
                imageUrl = it
            )

            // Scroll to bottom after message is sent
            lifecycleScope.launch {
                kotlinx.coroutines.delay(100)
                scrollToBottom()
            }
        }
    }

    companion object {
        const val TAG = "AssistantTranscriptDialog"

        fun newInstance(): AssistantTranscriptDialogFragment {
            return AssistantTranscriptDialogFragment()
        }
    }
}