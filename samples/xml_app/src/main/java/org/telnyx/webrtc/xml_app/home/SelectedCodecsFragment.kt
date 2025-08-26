package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.telnyx.webrtc.xmlapp.R

class SelectedCodecsFragment : Fragment() {
    private lateinit var adapter: SelectedCodecsAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyStateText: TextView
    private var selectedCodecs: MutableList<AudioCodec> = mutableListOf()
    private var onCodecRemoved: ((AudioCodec) -> Unit)? = null
    
    fun initialize(
        selectedCodecs: MutableList<AudioCodec>,
        onCodecRemoved: (AudioCodec) -> Unit
    ) {
        this.selectedCodecs = selectedCodecs
        this.onCodecRemoved = onCodecRemoved
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_selected_codecs, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        recyclerView = view.findViewById(R.id.selectedCodecsRecyclerView)
        emptyStateText = view.findViewById(R.id.emptyStateText)
        val hintText = view.findViewById<TextView>(R.id.selectedCodecsHint)
        
        adapter = SelectedCodecsAdapter(
            selectedCodecs = selectedCodecs,
            onCodecRemoved = { codec ->
                onCodecRemoved?.invoke(codec)
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
        
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
                adapter.moveItem(fromPosition, toPosition)
                return true
            }
            
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                // Not used
            }
        })
        
        itemTouchHelper.attachToRecyclerView(recyclerView)
        
        updateEmptyState()
        
        // Update hint text visibility
        hintText.visibility = if (selectedCodecs.isEmpty()) View.GONE else View.VISIBLE
    }
    
    fun updateAdapter() {
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
            updateEmptyState()
            
            // Update hint text visibility
            view?.findViewById<TextView>(R.id.selectedCodecsHint)?.visibility = 
                if (selectedCodecs.isEmpty()) View.GONE else View.VISIBLE
        }
    }
    
    private fun updateEmptyState() {
        if (selectedCodecs.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyStateText.visibility = View.VISIBLE
            emptyStateText.text = getString(R.string.no_codecs_selected_instruction)
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyStateText.visibility = View.GONE
        }
    }
}