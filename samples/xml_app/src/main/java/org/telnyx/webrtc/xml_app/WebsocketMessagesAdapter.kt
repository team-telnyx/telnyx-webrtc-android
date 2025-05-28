package org.telnyx.webrtc.xml_app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.GsonBuilder
import com.telnyx.webrtc.common.model.WebsocketMessage
import org.telnyx.webrtc.xmlapp.R
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter for displaying websocket messages in a RecyclerView.
 */
class WebsocketMessagesAdapter : RecyclerView.Adapter<WebsocketMessagesAdapter.MessageViewHolder>() {

    private val messages = mutableListOf<WebsocketMessage>()

    /**
     * Updates the list of messages and notifies the adapter.
     *
     * @param newMessages The new list of messages to display.
     */
    fun updateMessages(newMessages: List<WebsocketMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }

    /**
     * Clears all messages from the adapter.
     */
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ws_message, parent, false)
        return MessageViewHolder(view)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(messages[position], position)
    }

    override fun getItemCount(): Int = messages.size

    /**
     * ViewHolder for a websocket message item.
     */
    class MessageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val messageTitle: TextView = itemView.findViewById(R.id.messageTitle)
        private val messageContent: TextView = itemView.findViewById(R.id.messageContent)

        /**
         * Binds a message to the ViewHolder.
         *
         * @param websocketMessage The websocket message to display.
         * @param position The position of the message in the list.
         */
        fun bind(websocketMessage: WebsocketMessage, position: Int) {
            val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            messageTitle.text = "Message ${position + 1} - ${dateFormat.format(websocketMessage.timestamp)}"
            messageContent.text = GsonBuilder().setPrettyPrinting().create().toJson(websocketMessage.message)
        }
    }
}