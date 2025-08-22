package org.telnyx.webrtc.xml_app.home

import android.app.Dialog
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.telnyx.webrtc.xmlapp.R

class CodecSelectionDialogFragment(
    private val telnyxViewModel: TelnyxViewModel
) : DialogFragment() {

    private lateinit var availableCodecsAdapter: AvailableCodecsAdapter
    private lateinit var selectedCodecsAdapter: SelectedCodecsAdapter
    private val selectedCodecs = mutableListOf<AudioCodec>()
    
    private val availableCodecs = listOf(
        AudioCodec("audio/opus", 48000, 2),
        AudioCodec("audio/PCMU", 8000, 1),
        AudioCodec("audio/PCMA", 8000, 1),
        AudioCodec("audio/G722", 8000, 1),
        AudioCodec("audio/G729", 8000, 1),
        AudioCodec("audio/speex", 8000, 1),
        AudioCodec("audio/speex", 16000, 1),
        AudioCodec("audio/speex", 32000, 1)
    )

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_codec_selection, null)
        
        // Initialize selected codecs with current preferences
        selectedCodecs.clear()
        telnyxViewModel.getPreferredAudioCodecs()?.let { preferred ->
            selectedCodecs.addAll(preferred)
        }
        
        // Setup RecyclerViews
        setupAvailableCodecsRecyclerView(view)
        setupSelectedCodecsRecyclerView(view)
        
        // Setup buttons
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val applyButton = view.findViewById<Button>(R.id.applyButton)
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        applyButton.setOnClickListener {
            telnyxViewModel.setPreferredAudioCodecs(selectedCodecs.toList())
            Toast.makeText(
                requireContext(),
                "Preferred codecs updated: ${selectedCodecs.joinToString(", ") { it.mimeType }}",
                Toast.LENGTH_SHORT
            ).show()
            dialog.dismiss()
        }
        
        return dialog
    }
    
    private fun setupAvailableCodecsRecyclerView(view: android.view.View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.availableCodecsRecyclerView)
        
        availableCodecsAdapter = AvailableCodecsAdapter(
            codecs = availableCodecs,
            selectedCodecs = selectedCodecs,
            onCodecToggled = { codec, isSelected ->
                if (isSelected) {
                    if (!selectedCodecs.any { 
                        it.mimeType == codec.mimeType && 
                        it.clockRate == codec.clockRate && 
                        it.channels == codec.channels 
                    }) {
                        selectedCodecs.add(codec)
                        selectedCodecsAdapter.notifyItemInserted(selectedCodecs.size - 1)
                    }
                } else {
                    val index = selectedCodecs.indexOfFirst { 
                        it.mimeType == codec.mimeType && 
                        it.clockRate == codec.clockRate && 
                        it.channels == codec.channels 
                    }
                    if (index != -1) {
                        selectedCodecs.removeAt(index)
                        selectedCodecsAdapter.notifyItemRemoved(index)
                        selectedCodecsAdapter.notifyItemRangeChanged(index, selectedCodecs.size)
                    }
                }
                availableCodecsAdapter.notifyDataSetChanged()
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = availableCodecsAdapter
    }
    
    private fun setupSelectedCodecsRecyclerView(view: android.view.View) {
        val recyclerView = view.findViewById<RecyclerView>(R.id.selectedCodecsRecyclerView)
        
        selectedCodecsAdapter = SelectedCodecsAdapter(
            selectedCodecs = selectedCodecs,
            onCodecRemoved = { codec ->
                val index = selectedCodecs.indexOfFirst { 
                    it.mimeType == codec.mimeType && 
                    it.clockRate == codec.clockRate && 
                    it.channels == codec.channels 
                }
                if (index != -1) {
                    selectedCodecs.removeAt(index)
                    selectedCodecsAdapter.notifyItemRemoved(index)
                    selectedCodecsAdapter.notifyItemRangeChanged(index, selectedCodecs.size)
                    availableCodecsAdapter.notifyDataSetChanged()
                }
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = selectedCodecsAdapter
        
        // Setup drag and drop
        val itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                selectedCodecsAdapter.moveItem(fromPosition, toPosition)
                return true
            }
            
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }
        })
        
        itemTouchHelper.attachToRecyclerView(recyclerView)
    }
}