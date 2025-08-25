package org.telnyx.webrtc.xml_app.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.sdk.model.AudioCodec
import org.telnyx.webrtc.xmlapp.R
import java.util.Collections

class SelectedCodecsAdapter(
    private val selectedCodecs: MutableList<AudioCodec>,
    private val onCodecRemoved: (AudioCodec) -> Unit
) : RecyclerView.Adapter<SelectedCodecsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val codecIndex: TextView = view.findViewById(R.id.codecIndex)
        val codecName: TextView = view.findViewById(R.id.codecName)
        val codecDetails: TextView = view.findViewById(R.id.codecDetails)
        val removeButton: ImageView = view.findViewById(R.id.removeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_selected_codec, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val codec = selectedCodecs[position]
        
        holder.codecIndex.text = "${position + 1}."
        holder.codecName.text = codec.mimeType
        holder.codecDetails.text = "${codec.clockRate} Hz, ${codec.channels} ch"
        
        holder.removeButton.setOnClickListener {
            onCodecRemoved(codec)
        }
    }

    override fun getItemCount() = selectedCodecs.size

    fun moveItem(fromPosition: Int, toPosition: Int) {
        if (fromPosition < toPosition) {
            for (i in fromPosition until toPosition) {
                Collections.swap(selectedCodecs, i, i + 1)
            }
        } else {
            for (i in fromPosition downTo toPosition + 1) {
                Collections.swap(selectedCodecs, i, i - 1)
            }
        }
        notifyItemMoved(fromPosition, toPosition)
        // Update indices after move
        notifyItemRangeChanged(0, selectedCodecs.size)
    }
}