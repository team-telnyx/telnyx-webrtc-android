package org.telnyx.webrtc.xml_app.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.sdk.model.TranscriptItem
import org.telnyx.webrtc.xmlapp.R
import java.text.SimpleDateFormat
import java.util.*

class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.TranscriptViewHolder>() {

    private val transcriptItems = mutableListOf<TranscriptItem>()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun updateTranscript(items: List<TranscriptItem>) {
        val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = transcriptItems.size
            override fun getNewListSize() = items.size

            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean {
                return transcriptItems[oldPos].id == items[newPos].id
            }

            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                return transcriptItems[oldPos] == items[newPos]
            }
        })
        transcriptItems.clear()
        transcriptItems.addAll(items)
        diffResult.dispatchUpdatesTo(this)
        //notifyDataSetChanged()
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
        private val rvImages: RecyclerView = itemView.findViewById(R.id.rvImages)
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        private val imageAdapter = TranscriptImageAdapter()

        init {
            rvImages.layoutManager = LinearLayoutManager(itemView.context, LinearLayoutManager.HORIZONTAL, false)
            rvImages.adapter = imageAdapter
        }

        fun bind(item: TranscriptItem) {
            tvRole.text = when (item.role) {
                TranscriptItem.ROLE_USER -> "You"
                TranscriptItem.ROLE_ASSISTANT -> "Assistant"
                else -> item.role.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            }
            tvContent.text = item.content
            tvTimestamp.text = dateFormat.format(item.timestamp)

            // Handle images display
            item.images?.let { imagesList ->
                if (imagesList.isNotEmpty()) {
                    rvImages.visibility = View.VISIBLE
                    imageAdapter.updateImages(imagesList)
                } else {
                    rvImages.visibility = View.GONE
                }
            } ?: run {
                rvImages.visibility = View.GONE
            }
        }
    }
}