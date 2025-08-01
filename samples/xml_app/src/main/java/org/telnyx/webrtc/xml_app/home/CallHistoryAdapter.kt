package org.telnyx.webrtc.xml_app.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.common.model.CallHistoryItem
import com.telnyx.webrtc.common.model.CallType
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.CallHistoryItemBinding
import java.text.SimpleDateFormat
import java.util.*

class CallHistoryAdapter(
    private val onCallClick: (CallHistoryItem) -> Unit
) : ListAdapter<CallHistoryItem, CallHistoryAdapter.CallHistoryViewHolder>(CallHistoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CallHistoryViewHolder {
        val binding = CallHistoryItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CallHistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CallHistoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class CallHistoryViewHolder(
        private val binding: CallHistoryItemBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: CallHistoryItem) {
            binding.destinationNumber.text = item.destinationNumber
            binding.callDate.text = formatDate(item.date)

            // Set call type icon
            val iconRes = when (item.callType) {
                CallType.INBOUND -> R.drawable.ic_call_received
                CallType.OUTBOUND -> R.drawable.ic_call_made
            }
            binding.callTypeIcon.setImageResource(iconRes)
            binding.callTypeIcon.setColorFilter(
                binding.root.context.getColor(
                    when (item.callType) {
                        CallType.INBOUND -> R.color.reject_red
                        CallType.OUTBOUND -> R.color.answer_green
                    }
                )
            )
            // Set call button click listener
            binding.root.setOnClickListener {
                onCallClick(item)
            }
        }

        private fun formatDate(date: Long): String {
            val formatter = SimpleDateFormat("YYYY-MM-dd HH:mm:ss", Locale.getDefault())
            return formatter.format(date)
        }
    }

    private class CallHistoryDiffCallback : DiffUtil.ItemCallback<CallHistoryItem>() {
        override fun areItemsTheSame(oldItem: CallHistoryItem, newItem: CallHistoryItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: CallHistoryItem, newItem: CallHistoryItem): Boolean {
            return oldItem == newItem
        }
    }
}