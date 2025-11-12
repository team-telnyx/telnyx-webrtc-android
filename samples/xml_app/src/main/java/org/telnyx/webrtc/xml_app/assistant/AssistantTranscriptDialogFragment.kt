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
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xml_app.utils.Utils
import org.telnyx.webrtc.xmlapp.databinding.DialogAssistantTranscriptBinding

class AssistantTranscriptDialogFragment : BottomSheetDialogFragment() {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private var _binding: DialogAssistantTranscriptBinding? = null
    private val binding get() = _binding!!

    private lateinit var transcriptAdapter: TranscriptAdapter
    private var selectedImageBase64: String? = null

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
            sendMessage()
        }

        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnRemoveImage.setOnClickListener {
            clearImagePreview()
        }

        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }
    
    private fun sendMessage() {
        val message = binding.etMessage.text?.toString()?.trim() ?: ""

        // Only send if there's a message or image
        if (message.isNotEmpty() || selectedImageBase64 != null) {
            // Send message and image to AI assistant
            telnyxViewModel.sendAIAssistantMessage(
                requireContext(),
                message = message,
                imageUrl = selectedImageBase64
            )

            // Clear the message and image
            binding.etMessage.text?.clear()
            clearImagePreview()

            // Scroll to bottom after message is sent
            lifecycleScope.launch {
                kotlinx.coroutines.delay(100)
                scrollToBottom()
            }
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
            selectedImageBase64 = it
            showImagePreview(it)
        }
    }

    private fun showImagePreview(base64Image: String) {
        // Convert base64 to bitmap and display
        val bitmap = Utils.base64ToBitmap(base64Image)
        bitmap?.let {
            binding.ivImagePreview.setImageBitmap(it)
            binding.imagePreviewContainer.visibility = View.VISIBLE
        }
    }

    private fun clearImagePreview() {
        selectedImageBase64 = null
        binding.ivImagePreview.setImageBitmap(null)
        binding.imagePreviewContainer.visibility = View.GONE
    }

    companion object {
        const val TAG = "AssistantTranscriptDialog"

        fun newInstance(): AssistantTranscriptDialogFragment {
            return AssistantTranscriptDialogFragment()
        }
    }
}