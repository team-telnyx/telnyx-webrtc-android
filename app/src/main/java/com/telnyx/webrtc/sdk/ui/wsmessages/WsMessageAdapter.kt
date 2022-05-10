package com.telnyx.webrtc.sdk.ui.wsmessages

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.sdk.databinding.ItemWsmessagesBinding


class WsMessageAdapter(
    messageList: MutableList<String>?
) : RecyclerView.Adapter<WsMessagesViewHolder>() {

    private val wsMessagesList = mutableListOf<String>()

    init {
        if (messageList != null) {
            wsMessagesList.addAll(messageList)
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WsMessagesViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val itemBinding = ItemWsmessagesBinding.inflate(layoutInflater, parent, false)
        return WsMessagesViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: WsMessagesViewHolder, position: Int) {
        val message = wsMessagesList[position]
        holder.bind(message)
    }

    override fun getItemCount(): Int {
        return wsMessagesList.size
    }

    fun getWsMessages(): MutableList<String> = wsMessagesList

    fun addWsMessages(wsMessages: String) {
        wsMessagesList.add(wsMessages)
        notifyDataSetChanged()

    }

    fun clearWsMessages() {
        wsMessagesList.clear()
        notifyDataSetChanged()
    }
}

