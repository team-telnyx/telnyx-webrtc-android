package org.telnyx.webrtc.xml_app.assistant

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import org.telnyx.webrtc.xml_app.utils.Utils
import org.telnyx.webrtc.xmlapp.R

class ImagePreviewAdapter(
    private val onRemoveClick: (Int) -> Unit
) : RecyclerView.Adapter<ImagePreviewAdapter.ImagePreviewViewHolder>() {

    private val images = mutableListOf<String>()

    fun updateImages(newImages: List<String>) {
        images.clear()
        images.addAll(newImages)
        notifyDataSetChanged()
    }

    fun addImage(base64Image: String) {
        images.add(base64Image)
        notifyItemInserted(images.size - 1)
    }

    fun removeImage(position: Int) {
        if (position in images.indices) {
            images.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    fun getImages(): List<String> = images.toList()

    fun clearImages() {
        images.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImagePreviewViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_image_preview, parent, false)
        return ImagePreviewViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImagePreviewViewHolder, position: Int) {
        holder.bind(images[position], position)
    }

    override fun getItemCount(): Int = images.size

    inner class ImagePreviewViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivPreview: ImageView = itemView.findViewById(R.id.ivPreview)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)

        fun bind(base64Image: String, position: Int) {
            // Convert base64 to bitmap and display
            val bitmap = Utils.base64ToBitmap(base64Image)
            bitmap?.let {
                Glide.with(itemView.context)
                    .load(it)
                    .into(ivPreview)
            }

            btnRemove.setOnClickListener {
                onRemoveClick(position)
            }
        }
    }
}