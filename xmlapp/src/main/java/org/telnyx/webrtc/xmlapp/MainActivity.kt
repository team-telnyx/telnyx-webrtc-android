package org.telnyx.webrtc.xmlapp

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import com.telnyx.webrtc.common.TelnyxSessionState
import com.telnyx.webrtc.common.TelnyxViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.telnyx.webrtc.xmlapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val telnyxViewModel: TelnyxViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        bindEvents()
    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun bindEvents() {
        lifecycleScope.launch {
            telnyxViewModel.sessionsState.collect { sessionState ->
                when (sessionState) {
                    is TelnyxSessionState.ClientLogged -> {
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
                binding.progressIndicator.visibility = if (isLoading) View.VISIBLE else View.INVISIBLE
            }
        }
    }
}
