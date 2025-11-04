package org.telnyx.webrtc.xml_app.home

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.AudioCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.telnyx.webrtc.xmlapp.R

class CodecSelectionDialogFragment(
    private val telnyxViewModel: TelnyxViewModel
) : DialogFragment() {

    private val selectedCodecs = mutableListOf<AudioCodec>()
    private var availableCodecs: List<AudioCodec>? = null
    private var availableCodecsFragment: AvailableCodecsFragment? = null
    private var selectedCodecsFragment: SelectedCodecsFragment? = null
    private lateinit var loadingLayout: View
    private lateinit var viewPager: ViewPager2

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = layoutInflater.inflate(R.layout.dialog_codec_selection_tabbed, null)

        loadingLayout = view.findViewById(R.id.loadingLayout)
        viewPager = view.findViewById(R.id.viewPager)

        // Show loading indicator
        loadingLayout.visibility = View.VISIBLE
        viewPager.visibility = View.GONE

        // Initialize selected codecs with current preferences
        selectedCodecs.clear()
        telnyxViewModel.getPreferredAudioCodecs()?.let { preferred ->
            selectedCodecs.addAll(preferred)
        }

        // Setup buttons first (they don't depend on codecs)
        setupButtons(view)

        // Fetch available codecs from SDK asynchronously
        lifecycleScope.launch {
            val codecs = withContext(Dispatchers.IO) {
                telnyxViewModel.getSupportedAudioCodecs(requireContext())
            }
            availableCodecs = codecs

            // Hide loading and show content
            loadingLayout.visibility = View.GONE
            viewPager.visibility = View.VISIBLE

            // Setup ViewPager and TabLayout after codecs are loaded
            setupViewPagerAndTabs(view)
        }
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(view)
            .create()
        
        // Make the dialog larger
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.95).toInt(),
            (resources.displayMetrics.heightPixels * 0.85).toInt()
        )
        
        return dialog
    }
    
    private fun setupViewPagerAndTabs(view: View) {
        val viewPager = view.findViewById<ViewPager2>(R.id.viewPager)
        val tabLayout = view.findViewById<TabLayout>(R.id.tabLayout)
        
        val adapter = CodecPagerAdapter(requireActivity())
        viewPager.adapter = adapter
        
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            when (position) {
                0 -> tab.text = getString(R.string.codec_available_tab)
                1 -> tab.text = if (selectedCodecs.isNotEmpty()) getString(R.string.codec_selected_tab_count, selectedCodecs.size) else getString(R.string.codec_selected_tab)
            }
        }.attach()
        
        // Update tab text when selection changes
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == 1) {
                    tabLayout.getTabAt(1)?.text = if (selectedCodecs.isNotEmpty()) getString(R.string.codec_selected_tab_count, selectedCodecs.size) else getString(R.string.codec_selected_tab)
                }
            }
        })
    }
    
    private fun setupButtons(view: View) {
        val clearAllButton = view.findViewById<Button>(R.id.clearAllButton)
        val cancelButton = view.findViewById<Button>(R.id.cancelButton)
        val saveButton = view.findViewById<Button>(R.id.saveButton)
        
        clearAllButton.setOnClickListener {
            selectedCodecs.clear()
            availableCodecsFragment?.updateAdapter()
            selectedCodecsFragment?.updateAdapter()
            updateClearAllButton(clearAllButton)
        }
        
        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }
        
        saveButton.setOnClickListener {
            telnyxViewModel.setPreferredAudioCodecs(selectedCodecs.toList())
            Toast.makeText(
                requireContext(),
                getString(R.string.preferred_codecs_updated, selectedCodecs.joinToString(", ") { it.mimeType }),
                Toast.LENGTH_SHORT
            ).show()
            dialog?.dismiss()
        }
        
        updateClearAllButton(clearAllButton)
    }
    
    private fun updateClearAllButton(button: Button) {
        button.isEnabled = selectedCodecs.isNotEmpty()
    }
    
    inner class CodecPagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = 2
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> {
                    availableCodecsFragment = AvailableCodecsFragment()
                    availableCodecsFragment!!.initialize(
                        availableCodecs = availableCodecs ?: emptyList(),
                        selectedCodecs = selectedCodecs,
                        onCodecToggled = { codec, isSelected ->
                            if (isSelected) {
                                if (!selectedCodecs.any { 
                                    it.mimeType == codec.mimeType && 
                                    it.clockRate == codec.clockRate && 
                                    it.channels == codec.channels 
                                }) {
                                    selectedCodecs.add(codec)
                                }
                            } else {
                                selectedCodecs.removeIf { 
                                    it.mimeType == codec.mimeType && 
                                    it.clockRate == codec.clockRate && 
                                    it.channels == codec.channels 
                                }
                            }
                            availableCodecsFragment?.updateAdapter()
                            selectedCodecsFragment?.updateAdapter()
                            
                            // Update clear all button state
                            dialog?.findViewById<Button>(R.id.clearAllButton)?.let {
                                updateClearAllButton(it)
                            }
                            
                            // Update tab text
                            dialog?.findViewById<TabLayout>(R.id.tabLayout)?.getTabAt(1)?.text = 
                                if (selectedCodecs.isNotEmpty()) getString(R.string.codec_selected_tab_count, selectedCodecs.size) else getString(R.string.codec_selected_tab)
                        }
                    )
                    availableCodecsFragment!!
                }
                1 -> {
                    selectedCodecsFragment = SelectedCodecsFragment()
                    selectedCodecsFragment!!.initialize(
                        selectedCodecs = selectedCodecs,
                        onCodecRemoved = { codec ->
                            selectedCodecs.removeIf { 
                                it.mimeType == codec.mimeType && 
                                it.clockRate == codec.clockRate && 
                                it.channels == codec.channels 
                            }
                            availableCodecsFragment?.updateAdapter()
                            selectedCodecsFragment?.updateAdapter()
                            
                            // Update clear all button state
                            dialog?.findViewById<Button>(R.id.clearAllButton)?.let {
                                updateClearAllButton(it)
                            }
                            
                            // Update tab text
                            dialog?.findViewById<TabLayout>(R.id.tabLayout)?.getTabAt(1)?.text = 
                                if (selectedCodecs.isNotEmpty()) getString(R.string.codec_selected_tab_count, selectedCodecs.size) else getString(R.string.codec_selected_tab)
                        }
                    )
                    selectedCodecsFragment!!
                }
                else -> throw IllegalArgumentException(getString(R.string.invalid_position, position))
            }
        }
    }
}