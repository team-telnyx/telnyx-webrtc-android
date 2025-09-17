package org.telnyx.webrtc.xml_app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import com.karumi.dexter.Dexter
import com.karumi.dexter.MultiplePermissionsReport
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.multi.MultiplePermissionsListener
import com.telnyx.webrtc.common.TelnyxSessionState
import com.telnyx.webrtc.common.TelnyxSocketEvent
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.sdk.model.Region
import com.telnyx.webrtc.common.model.Profile
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.LegacyCallNotificationService
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.ConnectionStatus
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.BuildConfig
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.ActivityMainBinding
import androidx.appcompat.app.AlertDialog
import org.telnyx.webrtc.xml_app.home.PreCallDiagnosisBottomSheetFragment
import org.telnyx.webrtc.xml_app.assistant.AssistantLoginDialogFragment
import org.telnyx.webrtc.xml_app.home.CodecSelectionDialogFragment
import android.view.ViewGroup

class MainActivity : AppCompatActivity(), DefaultLifecycleObserver {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var gestureDetector: GestureDetector
    private lateinit var websocketMessagesAdapter: WebsocketMessagesAdapter

    private val telnyxViewModel: TelnyxViewModel by viewModels()
    private var lastShownErrorMessage: String? = null
    private var currentConnectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        FirebaseApp.initializeApp(this)
        lifecycle.addObserver(this)

        lifecycleScope.launch {
            telnyxViewModel.initProfile(this@MainActivity)
            checkPermission()
            handleCallNotification(intent)
        }

        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Set up Navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.loginFragment)
        )

        setupUI()
        setupGestureDetector()
        bindEvents()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallNotification(intent)
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        telnyxViewModel.disconnect(this)
    }

    private fun setupUI() {
        refreshVersionInfoText()

        // Initialize websocket messages adapter
        websocketMessagesAdapter = WebsocketMessagesAdapter()

        // Set up overflow menu
        binding.menuButton.setOnClickListener {
            showOverflowMenu()
        }
    }

    /**
     * Shows the overflow menu with options
     */
    private fun showOverflowMenu() {
        val popupMenu = androidx.appcompat.widget.PopupMenu(this, binding.menuButton)
        popupMenu.menuInflater.inflate(R.menu.overflow_menu, popupMenu.menu)

        val connectionStatus = currentConnectionStatus
        val isConnected = connectionStatus != ConnectionStatus.DISCONNECTED

        // Hide logged-in user options when not connected
        popupMenu.menu.findItem(R.id.action_websocket_messages).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_copy_fcm_token).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_disable_push).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_precall_diagnosis).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_prefetch_ice_candidates).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_trickle_ice).isVisible = isConnected
        popupMenu.menu.findItem(R.id.action_preferred_codecs).isVisible = isConnected

        // Show region selection for non-logged users
        popupMenu.menu.findItem(R.id.action_region_selection).isVisible = !isConnected

        // Show debug mode for non-logged users
        popupMenu.menu.findItem(R.id.action_debug_mode).isVisible = !isConnected

        // Show assistant login for non-logged users
        popupMenu.menu.findItem(R.id.action_assistant_login).isVisible = !isConnected

        // Update state of debug mode menu item
        popupMenu.menu.findItem(R.id.action_debug_mode).title =
            getString(if (telnyxViewModel.debugMode) R.string.debug_mode_off else R.string.debug_mode_on)

        // Add badge count to websocket messages menu item if there are messages
        val wsMessages = telnyxViewModel.wsMessages.value
        if (wsMessages.isNotEmpty()) {
            val menuItem = popupMenu.menu.findItem(R.id.action_websocket_messages)
            menuItem.title = getString(R.string.websocket_messages)
        }

        // Update region menu item title to show current selection
        val currentProfile = telnyxViewModel.currentProfile.value
        val currentRegion = currentProfile?.region ?: Region.AUTO
        popupMenu.menu.findItem(R.id.action_region_selection).title =
            getString(R.string.region_format, currentRegion.displayName)

        // Update prefetch ice candidates menu item title based on current state
        val prefetchMenuItem = popupMenu.menu.findItem(R.id.action_prefetch_ice_candidates)
        prefetchMenuItem.title = if (telnyxViewModel.prefetchIceCandidate) {
            getString(R.string.disable_prefetch_ice_candidates)
        } else {
            getString(R.string.enable_prefetch_ice_candidates)
        }

        // Update trickle ICE menu item title based on current state
        val trickleIceMenuItem = popupMenu.menu.findItem(R.id.action_trickle_ice)
        trickleIceMenuItem.title = if (telnyxViewModel.useTrickleIce) {
            getString(R.string.disable_trickle_ice)
        } else {
            getString(R.string.enable_trickle_ice)
        }

        popupMenu.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_websocket_messages -> {
                    showWebsocketMessagesBottomSheet()
                    true
                }

                R.id.action_copy_fcm_token -> {
                    copyFcmTokenToClipboard()
                    true
                }

                R.id.action_disable_push -> {
                    disablePushNotifications()
                    true
                }

                R.id.action_precall_diagnosis -> {
                    showPreCallDiagnosisBottomSheet()
                    true
                }

                R.id.action_prefetch_ice_candidates -> {
                    togglePrefetchIceCandidates()
                    true
                }

                R.id.action_trickle_ice -> {
                    toggleTrickleIce()
                    true
                }

                R.id.action_preferred_codecs -> {
                    showCodecSelectionDialog()
                    true
                }

                R.id.action_region_selection -> {
                    showRegionSelectionDialog()
                    true
                }

                R.id.action_debug_mode -> {
                    // Update debug mode in current profile or create a default profile
                    var currentDebugMode = telnyxViewModel.debugMode
                    currentDebugMode = !currentDebugMode

                    telnyxViewModel.updateDebugMode(currentDebugMode)
                    true
                }

                R.id.action_assistant_login -> {
                    showAssistantLoginDialog()
                    true
                }

                else -> false
            }
        }

        popupMenu.show()
    }

    /**
     * Shows a dialog for region selection
     */
    private fun showRegionSelectionDialog() {
        val regions = Region.values()
        val regionNames = regions.map { it.displayName }.toTypedArray()
        val currentProfile = telnyxViewModel.currentProfile.value
        val currentRegion = currentProfile?.region ?: Region.AUTO
        val selectedIndex = regions.indexOf(currentRegion)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.select_region))
            .setSingleChoiceItems(regionNames, selectedIndex) { dialog, which ->
                val selectedRegion = regions[which]

                // Update region in current profile or create a default profile
                if (currentProfile != null) {
                    telnyxViewModel.updateRegion(this, selectedRegion)
                } else {
                    val newProfile = Profile(region = selectedRegion)
                    telnyxViewModel.setCurrentConfig(this, newProfile)
                }

                dialog.dismiss()
                Toast.makeText(
                    this,
                    getString(R.string.region_set_to, selectedRegion.displayName),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Copies the FCM token to the clipboard
     */
    private fun copyFcmTokenToClipboard() {
        val token = telnyxViewModel.retrieveFCMToken()
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FCM Token", token))
        Toast.makeText(this, getString(R.string.fcm_token_copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * Disables push notifications
     */
    private fun disablePushNotifications() {
        telnyxViewModel.disablePushNotifications(this)
        Toast.makeText(this, R.string.push_notifications_disabled, Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggles the prefetch ICE candidates setting
     */
    private fun togglePrefetchIceCandidates() {
        val newState = !telnyxViewModel.prefetchIceCandidate
        telnyxViewModel.prefetchIceCandidate = newState
        val message = if (newState) {
            getString(R.string.enable_prefetch_ice_candidates)
        } else {
            getString(R.string.disable_prefetch_ice_candidates)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Toggles the trickle ICE setting
     */
    private fun toggleTrickleIce() {
        val newState = !telnyxViewModel.useTrickleIce
        telnyxViewModel.toggleTrickleIce(newState)
        val message = if (newState) {
            getString(R.string.enable_trickle_ice)
        } else {
            getString(R.string.disable_trickle_ice)
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /**
     * Shows the Assistant Login dialog
     */
    private fun showAssistantLoginDialog() {
        val dialog = AssistantLoginDialogFragment.newInstance()
        dialog.show(supportFragmentManager, AssistantLoginDialogFragment.TAG)
    }

    private fun bindEvents() {
        lifecycleScope.launch {
            telnyxViewModel.sessionsState.collect { sessionState ->
                when (sessionState) {
                    is TelnyxSessionState.ClientLoggedIn -> {
                        binding.socketStatusIcon.isEnabled = true
                        binding.socketStatusInfo.text = getString(R.string.client_ready)
                        binding.sessionId.text = sessionState.message.sessid

                        binding.bottomButton.text = getString(R.string.disconnect)
                        binding.bottomButton.setOnClickListener {
                            telnyxViewModel.disconnect(this@MainActivity)
                        }

                        binding.callState.visibility = View.VISIBLE
                        binding.callStateLabel.visibility = View.VISIBLE

                        // Show menu button when connected
                        binding.menuButton.visibility = View.VISIBLE

                        // Start collecting websocket messages
                        telnyxViewModel.collectWebsocketMessages()
                    }

                    is TelnyxSessionState.ClientDisconnected -> {
                        binding.socketStatusIcon.isEnabled = false
                        binding.socketStatusInfo.text = getString(R.string.disconnected)
                        binding.sessionId.text = getString(R.string.dash)

                        binding.bottomButton.text = getString(R.string.connect)
                        binding.bottomButton.setOnClickListener {
                            telnyxViewModel.currentProfile.value?.let { currentProfile ->
                                if (currentProfile.sipToken?.isEmpty() == false)
                                    telnyxViewModel.tokenLogin(
                                        this@MainActivity,
                                        currentProfile,
                                        null
                                    )
                                else
                                    telnyxViewModel.credentialLogin(
                                        this@MainActivity,
                                        currentProfile,
                                        null
                                    )
                            }
                        }

                        binding.callState.visibility = View.GONE
                        binding.callStateLabel.visibility = View.GONE

                        // Show menu button for region selection when disconnected
                        binding.menuButton.visibility = View.VISIBLE
                    }
                }
            }
        }

        lifecycleScope.launch {
            telnyxViewModel.sessionStateError.collect { error ->
                error?.let {
                    if (it != lastShownErrorMessage) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(getString(R.string.error))
                            .setMessage(it)
                            .setPositiveButton(android.R.string.ok) { dialog, which ->
                                dialog.dismiss()
                            }
                            .show()
                        lastShownErrorMessage = it
                    }
                }
            }
        }

        lifecycleScope.launch {
            telnyxViewModel.isLoading.collect { isLoading ->
                binding.progressIndicator.visibility =
                    if (isLoading) View.VISIBLE else View.INVISIBLE
            }
        }

        // Listen for connection status changes
        lifecycleScope.launch {
            telnyxViewModel.connectionStatus?.collect { connectionStatus ->
                updateConnectionStatus(connectionStatus)
            }
        }

        // Listen for call state changes:
        lifecycleScope.launch {
            telnyxViewModel.uiState.collect { uiState ->
                updateCallState(uiState)
            }
        }

        // Listen for websocket messages
        lifecycleScope.launch {
            telnyxViewModel.wsMessages.collect { messages ->
                // Update the adapter if the bottom sheet is showing
                websocketMessagesAdapter.updateMessages(messages)
            }
        }
    }

    private fun updateCallState(uiState: TelnyxSocketEvent) {
        val iconDrawable = when (uiState) {
            is TelnyxSocketEvent.OnIncomingCall -> R.drawable.incoming_indicator
            is TelnyxSocketEvent.OnCallEnded -> R.drawable.done_indicator
            is TelnyxSocketEvent.OnRinging -> R.drawable.ringing_indicator
            is TelnyxSocketEvent.OnCallDropped -> R.drawable.done_indicator
            is TelnyxSocketEvent.OnCallReconnecting -> R.drawable.ringing_indicator
            else -> R.drawable.status_circle // For Active, Init, Media, ClientReady
        }

        val callStateName = when (uiState) {
            is TelnyxSocketEvent.InitState -> getString(R.string.call_state_connecting)
            is TelnyxSocketEvent.OnIncomingCall -> getString(R.string.call_state_incoming)
            is TelnyxSocketEvent.OnCallEnded -> {
                val cause = uiState.message?.cause
                if (cause != null) "Done - $cause" else getString(R.string.call_state_ended)
            }

            is TelnyxSocketEvent.OnRinging -> getString(R.string.call_state_ringing)
            is TelnyxSocketEvent.OnCallDropped -> getString(R.string.call_state_dropped)
            is TelnyxSocketEvent.OnCallReconnecting -> getString(R.string.call_state_reconnecting)
            is TelnyxSocketEvent.OnCallAnswered -> getString(R.string.call_state_active)
            is TelnyxSocketEvent.OnMedia -> getString(R.string.call_state_active)
            is TelnyxSocketEvent.OnClientReady -> getString(R.string.client_ready)
        }
        binding.callStateIcon.setBackgroundResource(iconDrawable)
        binding.callStateInfo.text = callStateName
    }

    private fun updateConnectionStatus(connectionStatus: ConnectionStatus) {
        currentConnectionStatus = connectionStatus
        val statusText = when (connectionStatus) {
            ConnectionStatus.DISCONNECTED -> getString(R.string.disconnected)
            ConnectionStatus.CONNECTED -> getString(R.string.connected)
            ConnectionStatus.RECONNECTING -> getString(R.string.call_state_reconnecting)
            ConnectionStatus.CLIENT_READY -> getString(R.string.client_ready)
        }

        val isConnected = connectionStatus != ConnectionStatus.DISCONNECTED
        binding.socketStatusIcon.isEnabled = isConnected
        binding.socketStatusInfo.text = statusText
    }

    fun highlightButton(button: MaterialButton) {
        button.setBackgroundColor(ContextCompat.getColor(this, R.color.main_green))
        button.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }

    fun resetButton(button: MaterialButton) {
        button.setBackgroundColor(ContextCompat.getColor(this, android.R.color.white))
        button.setTextColor(ContextCompat.getColor(this, android.R.color.black))
    }

    private fun handleCallNotification(intent: Intent?) {

        if (intent == null) {
            return
        }

        val serviceIntent = Intent(this, LegacyCallNotificationService::class.java).apply {
            putExtra("action", LegacyCallNotificationService.STOP_ACTION)
        }
        serviceIntent.setAction(LegacyCallNotificationService.STOP_ACTION)
        startService(serviceIntent)

        val action = intent.extras?.getString(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)

        action?.let {
            val txPushMetaData =
                intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)
            when (action) {
                MyFirebaseMessagingService.ACT_ANSWER_CALL -> {
                    telnyxViewModel.answerIncomingPushCall(this, txPushMetaData, true)
                }

                MyFirebaseMessagingService.ACT_OPEN_TO_REPLY -> {
                    telnyxViewModel.connectWithLastUsedConfig(this, txPushMetaData)
                }
            }
        }
    }

    private fun setupGestureDetector() {
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // Only show environment bottom sheet when disconnected
                if (currentConnectionStatus == ConnectionStatus.DISCONNECTED) {
                    showEnvironmentBottomSheet()
                }
            }
        })

        binding.imageView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun showEnvironmentBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_environment, null)

        bottomSheetView.findViewById<View>(R.id.closeButton).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.devEnvironmentButton).setOnClickListener {
            telnyxViewModel.changeServerConfigEnvironment(true)
            Toast.makeText(
                this,
                R.string.switched_to_development,
                Toast.LENGTH_LONG
            ).show()
            refreshVersionInfoText()
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.prodEnvironmentButton).setOnClickListener {
            telnyxViewModel.changeServerConfigEnvironment(false)
            Toast.makeText(
                this,
                R.string.switched_to_production,
                Toast.LENGTH_LONG
            ).show()
            refreshVersionInfoText()
            bottomSheetDialog.dismiss()
        }

        // Hide FCM token and push notification buttons as they're now in the overflow menu
        bottomSheetView.findViewById<View>(R.id.copyFcmTokenButton).visibility = View.GONE
        bottomSheetView.findViewById<View>(R.id.disablePushButton).visibility = View.GONE

        bottomSheetDialog.setContentView(bottomSheetView)
        bottomSheetDialog.show()
    }

    private fun showPreCallDiagnosisBottomSheet() {
        val bottomSheet = PreCallDiagnosisBottomSheetFragment()
        bottomSheet.show(supportFragmentManager, PreCallDiagnosisBottomSheetFragment.TAG)

        // Start the diagnosis call
        lifecycleScope.launch {
            telnyxViewModel.makePreCallDiagnosis(
                this@MainActivity,
                BuildConfig.PRECALL_DIAGNOSIS_NUMBER
            )
        }
    }

    /**
     * Shows the codec selection dialog.
     */
    private fun showCodecSelectionDialog() {
        val dialog = CodecSelectionDialogFragment(telnyxViewModel)
        dialog.show(supportFragmentManager, "CodecSelectionDialog")
    }

    private fun checkPermission() {
        // Create a mutable list of permissions that will always be needed.
        val permissions = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO
        )

        // Conditionally add permissions based on the API level.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 34) { // Only available on Android 14 and above.
            permissions.add(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
        }

        // Now use Dexter to check the permissions.
        Dexter.withContext(this)
            .withPermissions(*permissions.toTypedArray())
            .withListener(object : MultiplePermissionsListener {
                override fun onPermissionsChecked(report: MultiplePermissionsReport?) {
                    report?.let {
                        if (report.areAllPermissionsGranted()) {
                            // All permissions are granted.
                        } else {
                            // Some permissions are denied.
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.notification_permission_text),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }

                override fun onPermissionRationaleShouldBeShown(
                    permissions: MutableList<PermissionRequest>?,
                    token: PermissionToken?
                ) {
                    token?.continuePermissionRequest()
                }
            }).check()
    }

    /**
     * Shows the websocket messages bottom sheet.
     */
    private fun showWebsocketMessagesBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val bottomSheetView = layoutInflater.inflate(R.layout.bottom_sheet_ws_messages, null)

        // Set bottom sheet height to 70% of screen height
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        bottomSheetView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (screenHeight * 0.8).toInt()
        )

        // Set up close button
        bottomSheetView.findViewById<View>(R.id.closeButton).setOnClickListener {
            bottomSheetDialog.dismiss()
        }

        // Set up clear messages button
        bottomSheetView.findViewById<View>(R.id.clearMessagesButton).setOnClickListener {
            telnyxViewModel.clearWebsocketMessages()
            bottomSheetDialog.dismiss()
        }

        // Set up recycler view
        val recyclerView = bottomSheetView.findViewById<RecyclerView>(R.id.messagesRecyclerView)
        recyclerView.adapter = websocketMessagesAdapter

        // Show empty text if no messages
        val emptyText = bottomSheetView.findViewById<TextView>(R.id.emptyMessagesText)
        if (telnyxViewModel.wsMessages.value.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
        }

        bottomSheetDialog.setContentView(bottomSheetView)
        bottomSheetDialog.show()
    }

    private fun refreshVersionInfoText() {
        binding.apply {
            val environmentLabel = if (telnyxViewModel.serverConfigurationIsDev) {
                getString(R.string.development_label)
            } else {
                getString(R.string.production_label)
            }.replaceFirstChar { it.uppercaseChar() }

            versionInfo.text = String.format(
                getString(R.string.bottom_bar_production_text),
                environmentLabel,
                TelnyxClient.SDK_VERSION.toString(),
                BuildConfig.VERSION_NAME
            )
        }
    }
}
