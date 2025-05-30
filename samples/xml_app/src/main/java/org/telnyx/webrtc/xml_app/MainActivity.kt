package org.telnyx.webrtc.xml_app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
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
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.LegacyCallNotificationService
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.BuildConfig
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.ActivityMainBinding
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity(), DefaultLifecycleObserver {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var gestureDetector: GestureDetector

    private val telnyxViewModel: TelnyxViewModel by viewModels()
    private var lastShownErrorMessage: String? = null

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
                    }

                    is TelnyxSessionState.ClientDisconnected -> {
                        binding.socketStatusIcon.isEnabled = false
                        binding.socketStatusInfo.text = getString(R.string.disconnected)
                        binding.sessionId.text = getString(R.string.dash)

                        binding.bottomButton.text = getString(R.string.connect)
                        binding.bottomButton.setOnClickListener {
                            telnyxViewModel.currentProfile.value?.let { currentProfile ->
                                if (currentProfile.sipToken?.isEmpty() == false)
                                    telnyxViewModel.tokenLogin(this@MainActivity, currentProfile,null)
                                else
                                    telnyxViewModel.credentialLogin(this@MainActivity, currentProfile,null)
                            }
                        }

                        binding.callState.visibility = View.GONE
                        binding.callStateLabel.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            telnyxViewModel.sessionStateError.collect { error ->
                error?.let {
                    if (it != lastShownErrorMessage) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Error")
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

        // Listen for call state changes:
        lifecycleScope.launch {
            telnyxViewModel.uiState.collect { uiState ->
                updateCallState(uiState)
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
                showEnvironmentBottomSheet()
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

        bottomSheetView.findViewById<View>(R.id.copyFcmTokenButton).setOnClickListener {
            val token = telnyxViewModel.retrieveFCMToken()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("FCM Token", token))
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.disablePushButton).setOnClickListener {
            telnyxViewModel.disablePushNotifications(this)
            Toast.makeText(
                this,
                R.string.push_notifications_disabled,
                Toast.LENGTH_LONG
            ).show()
            bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.setContentView(bottomSheetView)
        bottomSheetDialog.show()
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

    private fun refreshVersionInfoText() {
        binding.apply {
            val environmentLabel = if (telnyxViewModel.serverConfigurationIsDev) {
                getString(R.string.development_label)
            } else {
                getString(R.string.production_label)
            }.replaceFirstChar { it.uppercaseChar() }

            versionInfo.text = String.format(getString(R.string.bottom_bar_production_text), environmentLabel, TelnyxClient.SDK_VERSION.toString(), BuildConfig.VERSION_NAME)
        }
    }
}
