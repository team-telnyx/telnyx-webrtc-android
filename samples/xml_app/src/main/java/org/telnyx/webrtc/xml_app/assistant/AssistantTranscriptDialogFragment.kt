package org.telnyx.webrtc.xml_app.assistant

import android.app.Dialog
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.TranscriptItem
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import java.util.*

class AssistantTranscriptDialogFragment : DialogFragment() {
    
    private val telnyxViewModel: TelnyxViewModel by activityViewModels()
    private lateinit var transcriptAdapter: TranscriptAdapter
    private lateinit var rvTranscript: RecyclerView
    private lateinit var etMessage: TextInputEditText
    private lateinit var btnSend: MaterialButton
    
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_assistant_transcript, null)
        
        setupViews(view)
        setupRecyclerView()
        setupListeners()
        observeTranscript()
        
        return AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
    }
    
    private fun setupViews(view: View) {
        rvTranscript = view.findViewById(R.id.rvTranscript)
        etMessage = view.findViewById(R.id.etMessage)
        btnSend = view.findViewById(R.id.btnSend)
    }
    
    private fun setupRecyclerView() {
        transcriptAdapter = TranscriptAdapter()
        rvTranscript.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = transcriptAdapter
        }
    }
    
    private fun setupListeners() {
        btnSend.setOnClickListener {
            val message = etMessage.text?.toString()?.trim()
            if (!message.isNullOrEmpty()) {
                sendMessage(message)
                etMessage.text?.clear()
            }
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
        // This would observe actual transcript updates from the TelnyxViewModel
        // For now, we'll leave this as a placeholder for future implementation
        lifecycleScope.launch {
            // telnyxViewModel.transcriptItems.collect { items ->
            //     transcriptAdapter.updateTranscript(items)
            //     scrollToBottom()
            // }
        }
    }
    
    private fun scrollToBottom() {
        if (transcriptAdapter.itemCount > 0) {
            rvTranscript.scrollToPosition(transcriptAdapter.itemCount - 1)
        }
    }
    
    companion object {
        const val TAG = "AssistantTranscriptDialog"
        
        fun newInstance(): AssistantTranscriptDialogFragment {
            return AssistantTranscriptDialogFragment()
        }
    }
}