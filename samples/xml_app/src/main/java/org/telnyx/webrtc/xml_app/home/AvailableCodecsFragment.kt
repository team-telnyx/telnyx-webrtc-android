package org.telnyx.webrtc.xml_app.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.telnyx.webrtc.xmlapp.R

class AvailableCodecsFragment : Fragment() {
    private lateinit var adapter: AvailableCodecsAdapter
    private var availableCodecs: List<AudioCodec> = emptyList()
    private var selectedCodecs: MutableList<AudioCodec> = mutableListOf()
    private var onCodecToggled: ((AudioCodec, Boolean) -> Unit)? = null
    
    fun initialize(
        availableCodecs: List<AudioCodec>,
        selectedCodecs: MutableList<AudioCodec>,
        onCodecToggled: (AudioCodec, Boolean) -> Unit
    ) {
        this.availableCodecs = availableCodecs
        this.selectedCodecs = selectedCodecs
        this.onCodecToggled = onCodecToggled
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_available_codecs, container, false)
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        val recyclerView = view.findViewById<RecyclerView>(R.id.availableCodecsRecyclerView)
        
        adapter = AvailableCodecsAdapter(
            codecs = availableCodecs,
            selectedCodecs = selectedCodecs,
            onCodecToggled = { codec, isSelected ->
                onCodecToggled?.invoke(codec, isSelected)
            }
        )
        
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }
    
    fun updateAdapter() {
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }
}