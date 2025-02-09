package org.telnyx.webrtc.xmlapp.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.telnyx.webrtc.common.model.Profile
import org.telnyx.webrtc.xmlapp.databinding.ProfileListBinding

enum class ProfileAction {
    DELETE_PROFILE,
    EDIT_PROFILE,
    SELECT_PROFILE
}

class ProfileListAdapter(private val onClick: (Profile,ProfileAction) -> Unit) :
    ListAdapter<Profile, ProfileListAdapter.ProfileViewHolder>(ProfileDiffCallback()) {

    // ViewHolder class
    class ProfileViewHolder(val binding: ProfileListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile) {
            binding.profileName.text = profile.sipUsername
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ProfileListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = getItem(position) // Use getItem() to retrieve the item at the given position
        holder.binding.root.setOnClickListener {
            onClick(profile,ProfileAction.SELECT_PROFILE)
        }
        holder.binding.deleteProfile.setOnClickListener {
            onClick(profile,ProfileAction.DELETE_PROFILE)
        }
        holder.binding.editProfile.setOnClickListener {
            onClick(profile,ProfileAction.EDIT_PROFILE)
        }
        holder.bind(profile)
    }
}

class ProfileDiffCallback : DiffUtil.ItemCallback<Profile>() {
    override fun areItemsTheSame(oldItem: Profile, newItem: Profile): Boolean {
        // Use a unique identifier to determine if two items are the same
        return oldItem.sipUsername == newItem.sipUsername
    }

    override fun areContentsTheSame(oldItem: Profile, newItem: Profile): Boolean {
        // Check if the contents of the items are the same
        return oldItem == newItem
    }
}
