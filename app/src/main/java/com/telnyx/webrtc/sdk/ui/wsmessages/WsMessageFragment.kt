package com.telnyx.webrtc.sdk.ui.wsmessages

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.telnyx.webrtc.sdk.BuildConfig
import com.telnyx.webrtc.sdk.R
import com.telnyx.webrtc.sdk.databinding.FragmentWsmessageBinding
import com.telnyx.webrtc.sdk.model.WsMessageData
import com.telnyx.webrtc.sdk.ui.MainViewModel
import com.telnyx.webrtc.sdk.verto.receive.SocketObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream


class WsMessageFragment : Fragment() {
    private lateinit var binding: FragmentWsmessageBinding
    private val mainViewModel: MainViewModel by activityViewModels()
    private var receivedWsMessageList = emptyList<String>()

    private var wsMessageAdapter: WsMessageAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentWsmessageBinding.inflate(inflater, container, false)
        receivedWsMessageList = arguments?.getStringArrayList(WSMESSAGES_LIST)!!

        return binding.root
    }

    override fun onResume() {
        super.onResume()
        setAdapter()
        observeWsMessages()
        setupViews()
    }

    private fun setAdapter() {
        wsMessageAdapter =
            WsMessageAdapter(
                receivedWsMessageList.toMutableList()
            )

        binding.recyclerViewWsMessages.adapter = wsMessageAdapter
        wsMessageAdapter?.let {
            val lastPosition = it.itemCount - 1
            if (lastPosition >= 0) {
                binding.recyclerViewWsMessages.layoutManager =
                    LinearLayoutManager(context).apply {
                        scrollToPositionWithOffset(lastPosition, 0)
                    }
            }
        }
    }

    private fun observeWsMessages() {
        mainViewModel.getWsMessageResponse()?.observe(
            this.viewLifecycleOwner, object : SocketObserver<WsMessageData>() {
                override fun onConnectionEstablished() {}

                override fun onMessageReceived(data: WsMessageData?) {
                    data?.wsMessageJsonObject?.let {
                        wsMessageAdapter?.addWsMessages(it.toString())
                    }
                }

                override fun onLoading() {}

                override fun onError(message: String?) {}
            }
        )
    }


    private fun setupViews() {
        binding.buttonClearwsmessage.setOnClickListener {
            wsMessageAdapter?.clearWsMessages()
        }

        binding.buttonSharewsmessage.setOnClickListener {
            val wsMessages = wsMessageAdapter?.getWsMessages()
            if (wsMessages.isNullOrEmpty()) {
                Toast.makeText(requireContext(), R.string.empty_wsmessages, Toast.LENGTH_SHORT)
                    .show()
            } else {
                shareWsMessageLog(wsMessages)
            }
        }
    }

    private fun shareWsMessageLog(wsMessages: MutableList<String>) {
        val file = File(requireContext().getExternalFilesDir(null), "wsmessages.txt")
        val outputStream = FileOutputStream(file)
        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.IO) {
                wsMessages.forEach {

                    outputStream.write(it.toByteArray())

                }
                outputStream.close()

                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    BuildConfig.APPLICATION_ID, file
                )

                val shareIntent = Intent().apply {
                    type = "text/plain"
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(
                    Intent.createChooser(
                        shareIntent,
                        "Share to"
                    )
                )
            }
        }
    }

    companion object {
        const val WSMESSAGES_LIST = "wsmessages_list"

        @JvmStatic
        fun newInstance(wsMesssageList: ArrayList<String>?) =
            WsMessageFragment().apply {
                arguments = Bundle().apply {
                    putStringArrayList(WSMESSAGES_LIST, wsMesssageList)
                }
            }
    }
}