package org.telnyx.webrtc.xml_app.assistant

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
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
import java.io.File

class AssistantTranscriptDialogFragment : BottomSheetDialogFragment() {

    private val telnyxViewModel: TelnyxViewModel by activityViewModels()

    private var _binding: DialogAssistantTranscriptBinding? = null
    private val binding get() = _binding!!

    private lateinit var transcriptAdapter: TranscriptAdapter
    private lateinit var imagePreviewAdapter: ImagePreviewAdapter
    private var photoUri: Uri? = null

    // Image picker launcher
    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            handleImageSelection(selectedUri)
        }
    }

    // Camera launcher
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoUri != null) {
            handleImageSelection(photoUri!!)
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
        // Setup transcript adapter
        transcriptAdapter = TranscriptAdapter()
        binding.rvTranscript.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = transcriptAdapter
        }

        // Setup image preview adapter
        imagePreviewAdapter = ImagePreviewAdapter { position ->
            imagePreviewAdapter.removeImage(position)
            updateImagePreviewVisibility()
        }
        binding.rvImagePreview.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = imagePreviewAdapter
        }
    }

    private fun setupListeners() {
        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.btnAddImage.setOnClickListener {
            imagePickerLauncher.launch("image/*")
        }

        binding.btnCamera.setOnClickListener {
            openCamera()
        }

        binding.closeButton.setOnClickListener {
            dismiss()
        }
    }

    private fun sendMessage() {
        val message = binding.etMessage.text?.toString()?.trim() ?: ""
        val images = imagePreviewAdapter.getImages()

        // Only send if there's a message or images
        if (message.isNotEmpty() || images.isNotEmpty()) {
            // Send message and images to AI assistant
            telnyxViewModel.sendAIAssistantMessage(
                requireContext(),
                message = message,
                imagesUrls = images.ifEmpty { null }
            )

            // Clear the message and images
            binding.etMessage.text?.clear()
            imagePreviewAdapter.clearImages()
            updateImagePreviewVisibility()

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
            imagePreviewAdapter.addImage(it)
            updateImagePreviewVisibility()
        }
    }

    private fun updateImagePreviewVisibility() {
        binding.imagePreviewScrollView.visibility = if (imagePreviewAdapter.itemCount > 0) {
            View.VISIBLE
        } else {
            View.GONE
        }
    }

    private fun openCamera() {
        photoUri = createImageFileUri()
        photoUri?.let {
            cameraLauncher.launch(it)
        }
    }

    private fun createImageFileUri(): Uri? {
        return try {
            val imageFile = File(
                requireContext().cacheDir,
                "camera_photo_${System.currentTimeMillis()}.jpg"
            )
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                imageFile
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    companion object {
        const val TAG = "AssistantTranscriptDialog"

        fun newInstance(): AssistantTranscriptDialogFragment {
            return AssistantTranscriptDialogFragment()
        }
    }
}