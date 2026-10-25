package com.viswa2k.smsforwarder

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.viswa2k.smsforwarder.service.ForwarderService
import com.viswa2k.smsforwarder.ui.PlaceholderScreen
import com.viswa2k.smsforwarder.update.UpdateDialogHost
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Create an extension property for DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {

    private lateinit var userPreferences: UserPreferences

    companion object {
        private const val REQUEST_SMS_PERMISSIONS = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize DataStore and UserPreferences
        userPreferences = UserPreferences(dataStore)

        // Show the privacy policy only until the user acknowledges it once.
        if (!isPrivacyAccepted()) showPrivacyPolicy()

        // Initialize defaults
        lifecycleScope.launch {
            userPreferences.initializeDefaults()
            checkAndStartService()
        }

        // Check for battery optimization exemption
        checkBatteryOptimization()

        // Request permissions with clear explanations
        requestPermissions()

        // Set the content view
        setContent {
            com.viswa2k.smsforwarder.ui.theme.SMSForwarderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    PlaceholderScreen()
                    // Checks GitHub Releases on launch; prompts to install if a newer APK exists.
                    UpdateDialogHost()
                }
            }
        }
    }

    private fun isPrivacyAccepted(): Boolean =
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("privacy_accepted", false)

    private fun showPrivacyPolicy() {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_policy_title))
            .setMessage(getString(R.string.privacy_policy_summary))
            .setPositiveButton("I Understand") { dialog, _ ->
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("privacy_accepted", true).apply()
                dialog.dismiss()
            }
            .setCancelable(false)

        builder.show()
    }

    private fun checkBatteryOptimization() {
        try {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Request battery optimization exemption
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("BatteryOptimization", "Error checking battery optimization: ${e.message}")
        }
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
            permissionsToRequest.add(Manifest.permission.READ_SMS)
            permissionsToRequest.add(Manifest.permission.RECEIVE_SMS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_SMS_PERMISSIONS
            )
        }
    }

    override fun onStop() {
        super.onStop()
    }

    private suspend fun checkAndStartService() {
        if (AppGraph.from(this).settings.masterOn.first()) ForwarderService.start(this)
    }

}
