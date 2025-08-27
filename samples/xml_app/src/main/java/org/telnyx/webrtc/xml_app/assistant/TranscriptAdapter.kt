package org.telnyx.webrtc.xml_app.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.sdk.model.TranscriptItem
import org.telnyx.webrtc.xmlapp.R
import java.text.SimpleDateFormat
import java.util.*

class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.TranscriptViewHolder>() {
    
    private val transcriptItems = mutableListOf<TranscriptItem>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    
    fun updateTranscript(items: List<TranscriptItem>) {
        transcriptItems.clear()
        transcriptItems.addAll(items)
        notifyDataSetChanged()
    }
    
    fun addTranscriptItem(item: TranscriptItem) {
        transcriptItems.add(item)
        notifyItemInserted(transcriptItems.size - 1)
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranscriptViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transcript, parent, false)
        return TranscriptViewHolder(view)
    }
    
    override fun onBindViewHolder(holder: TranscriptViewHolder, position: Int) {
        holder.bind(transcriptItems[position])
    }
    
    override fun getItemCount(): Int = transcriptItems.size
    
    class TranscriptViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvRole: TextView = itemView.findViewById(R.id.tvRole)
        private val tvContent: TextView = itemView.findViewById(R.id.tvContent)
        private val tvTimestamp: TextView = itemView.findViewById(R.id.tvTimestamp)
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        
        fun bind(item: TranscriptItem) {
            tvRole.text = when (item.role) {
                TranscriptItem.ROLE_USER -> "You"
                TranscriptItem.ROLE_ASSISTANT -> "Assistant"
                else -> item.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            tvContent.text = item.content
            tvTimestamp.text = dateFormat.format(item.timestamp)
        }
    }
}