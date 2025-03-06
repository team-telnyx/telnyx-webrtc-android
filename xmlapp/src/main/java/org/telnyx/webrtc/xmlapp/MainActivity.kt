package org.telnyx.webrtc.xmlapp

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.telnyx.webrtc.xmlapp.databinding.ActivityMainBinding
import org.telnyx.webrtc.xmlapp.databinding.BottomSheetDevOptionsBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    private lateinit var appBarConfiguration: AppBarConfiguration

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

        // Set up long press listener for logo
        binding.imageView.setOnLongClickListener {
            showDevOptionsBottomSheet()
            true
        }

        // Set up Navigation
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController

        appBarConfiguration = AppBarConfiguration(
            setOf(R.id.loginFragment)
        )

    }

    override fun onSupportNavigateUp(): Boolean {
        return navController.navigateUp() || super.onSupportNavigateUp()
    }

    private fun showDevOptionsBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val bottomSheetBinding = BottomSheetDevOptionsBinding.inflate(layoutInflater)
        bottomSheetDialog.setContentView(bottomSheetBinding.root)

        bottomSheetBinding.apply {
            btnDevEnv.setOnClickListener {
                // TODO: Implement development environment switch
                bottomSheetDialog.dismiss()
            }

            btnProdEnv.setOnClickListener {
                // TODO: Implement production environment switch
                bottomSheetDialog.dismiss()
            }

            btnCopyFcm.setOnClickListener {
                // TODO: Implement FCM token copy
                bottomSheetDialog.dismiss()
            }

            btnDisablePush.setOnClickListener {
                // TODO: Implement push notifications disable
                bottomSheetDialog.dismiss()
            }
        }

        bottomSheetDialog.show()
    }

}
