package org.telnyx.webrtc.xmlapp.login

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.telnyx.webrtc.xmlapp.databinding.ProfileListBinding

class ProfileListAdapter(private val profileList: List<Profile>) :
    RecyclerView.Adapter<ProfileListAdapter.ProfileViewHolder>() {

    // ViewHolder class
    class ProfileViewHolder(val binding: ProfileListBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(profile: Profile) {
            binding.profileName.text = profile.name
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
        val profile = profileList[position]
        holder.bind(profile)
    }

    override fun getItemCount() = profileList.size

}

data class Profile(val name: String, val email: String)


