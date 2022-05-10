package com.telnyx.webrtc.sdk.ui.wsmessages

import androidx.recyclerview.widget.RecyclerView
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.databinding.ItemWsmessagesBinding


class WsMessagesViewHolder(private val binding: ItemWsmessagesBinding) :
    RecyclerView.ViewHolder(binding.root) {

    fun bind(wsMessages: String) {
        val gson = GsonBuilder().setPrettyPrinting().create()
        val wsMessageJson = gson.fromJson(wsMessages, JsonObject::class.java)
        val formattedWsMessageJson = gson.toJson(wsMessageJson)

        binding.textviewWsmessages.text = formattedWsMessageJson
    }


}