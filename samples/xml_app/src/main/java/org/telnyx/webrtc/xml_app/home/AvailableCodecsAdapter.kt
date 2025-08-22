package org.telnyx.webrtc.xml_app.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.telnyx.webrtc.xmlapp.R

class AvailableCodecsAdapter(
    private val codecs: List<AudioCodec>,
    private val selectedCodecs: MutableList<AudioCodec>,
    private val onCodecToggled: (AudioCodec, Boolean) -> Unit
) : RecyclerView.Adapter<AvailableCodecsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val codecCheckbox: CheckBox = view.findViewById(R.id.codecCheckbox)
        val codecName: TextView = view.findViewById(R.id.codecName)
        val codecDetails: TextView = view.findViewById(R.id.codecDetails)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_available_codec, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val codec = codecs[position]
        
        holder.codecName.text = codec.mimeType
        holder.codecDetails.text = "${codec.clockRate} Hz, ${codec.channels} ch"
        
        val isSelected = selectedCodecs.any { 
            it.mimeType == codec.mimeType && 
            it.clockRate == codec.clockRate && 
            it.channels == codec.channels 
        }
        holder.codecCheckbox.isChecked = isSelected
        
        holder.codecCheckbox.setOnCheckedChangeListener { _, isChecked ->
            onCodecToggled(codec, isChecked)
        }
        
        holder.itemView.setOnClickListener {
            holder.codecCheckbox.isChecked = !holder.codecCheckbox.isChecked
        }
    }

    override fun getItemCount() = codecs.size
}