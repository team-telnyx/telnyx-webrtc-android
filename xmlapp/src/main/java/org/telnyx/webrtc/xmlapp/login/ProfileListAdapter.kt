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
            // Bind data to the views in the layout
            binding.profileName.text = profile.name
            // ... bind other data as needed
        }
    }

    // Create new views (invoked by the layout manager)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProfileViewHolder {
        val binding = ProfileListBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ProfileViewHolder(binding)
    }

    // Replace the contents of a view (invoked by the layout manager)
    override fun onBindViewHolder(holder: ProfileViewHolder, position: Int) {
        val profile = profileList[position]
        holder.bind(profile)
    }

    // Return the size of your dataset (invoked by the layout manager)
    override fun getItemCount() = profileList.size
}

// Example Profile data class (replace with your actual data class)
data class Profile(val name: String, val email: String)