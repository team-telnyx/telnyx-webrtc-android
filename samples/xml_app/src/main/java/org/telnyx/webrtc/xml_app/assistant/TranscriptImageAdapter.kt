package org.telnyx.webrtc.xml_app.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telnyx.webrtc.xml_app.utils.Utils
import org.telnyx.webrtc.xmlapp.R

class TranscriptImageAdapter : RecyclerView.Adapter<TranscriptImageAdapter.ImageViewHolder>() {

    private val images = mutableListOf<String>()

    fun updateImages(newImages: List<String>) {
        images.clear()
        images.addAll(newImages)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transcript_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        holder.bind(images[position])
    }

    override fun getItemCount(): Int = images.size

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivTranscriptImage: ImageView = itemView.findViewById(R.id.ivTranscriptImage)

        fun bind(base64Image: String) {
            val bitmap = Utils.base64ToBitmap(base64Image)
            bitmap?.let {
                Glide.with(itemView.context)
                    .load(it)
                    .into(ivTranscriptImage)
            }
        }
    }
}