package org.telnyx.webrtc.xml_app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import android.view.GestureDetector
import android.view.MotionEvent
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import com.telnyx.webrtc.common.TelnyxSessionState
import com.telnyx.webrtc.common.TelnyxViewModel
import com.telnyx.webrtc.common.notification.MyFirebaseMessagingService
import com.telnyx.webrtc.common.notification.NotificationsService
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.R
import org.telnyx.webrtc.xmlapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), DefaultLifecycleObserver {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var gestureDetector: GestureDetector

    private val telnyxViewModel: TelnyxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super<AppCompatActivity>.onCreate(savedInstanceState)
        lifecycle.addObserver(this)

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

        checkPermission()
        handleCallNotification(intent)
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

    override fun onStart(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStart(owner)
        telnyxViewModel.connectWithLastUsedConfig(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        super<DefaultLifecycleObserver>.onStop(owner)
        telnyxViewModel.disconnect(this, false)
    }

    private fun bindEvents() {
        lifecycleScope.launch {
            telnyxViewModel.sessionsState.collect { sessionState ->
                when (sessionState) {
                    is TelnyxSessionState.ClientLoggedIn -> {
                        binding.socketStatusIcon.isEnabled = true
                        binding.socketStatusInfo.text = getString(R.string.client_ready)
                        binding.sessionId.text = sessionState.message.sessid
                    }

                    is TelnyxSessionState.ClientDisconnected -> {
                        binding.socketStatusIcon.isEnabled = false
                        binding.socketStatusInfo.text = getString(R.string.disconnected)
                        binding.sessionId.text = getString(R.string.dash)
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
    }

    fun updateCallState(callState: String) {
        findViewById<TextView>(R.id.callState).text = callState
    }

    private fun handleCallNotification(intent: Intent?) {

        if (intent == null) {
            return
        }

        val serviceIntent = Intent(this, NotificationsService::class.java).apply {
            putExtra("action", NotificationsService.STOP_ACTION)
        }
        serviceIntent.setAction(NotificationsService.STOP_ACTION)
        startService(serviceIntent)

        val action = intent.extras?.getString(MyFirebaseMessagingService.EXT_KEY_DO_ACTION)

        action?.let {
            val txPushMetaData =
                intent.extras?.getString(MyFirebaseMessagingService.TX_PUSH_METADATA)
            if (action == MyFirebaseMessagingService.ACT_ANSWER_CALL) {
                telnyxViewModel.answerIncomingPushCall(this, txPushMetaData)
            } else if (action == MyFirebaseMessagingService.ACT_REJECT_CALL) {
                telnyxViewModel.rejectIncomingPushCall(this, txPushMetaData)
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
            bottomSheetDialog.dismiss()
        }

        bottomSheetView.findViewById<View>(R.id.prodEnvironmentButton).setOnClickListener {
            telnyxViewModel.changeServerConfigEnvironment(false)
            Toast.makeText(
                this,
                R.string.switched_to_production,
                Toast.LENGTH_LONG
            ).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Dexter.withContext(this)
                .withPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                .withListener(object : PermissionListener {
                    override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                        // Permission granted
                    }

                    override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                        // Permission denied
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.notification_permission_text),
                            Toast.LENGTH_LONG
                        ).show()
                    }

                    override fun onPermissionRationaleShouldBeShown(
                        permission: PermissionRequest?,
                        token: PermissionToken?
                    ) {
                        token?.continuePermissionRequest()
                    }
                }).check()
        }
    }
}
