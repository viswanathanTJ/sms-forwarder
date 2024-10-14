package com.viswa2k.smsforwarder

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.viswa2k.smsforwarder.ui.SettingsScreen
import kotlinx.coroutines.launch

// Create an extension property for DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {

    private lateinit var userPreferences: UserPreferences

    companion object {
        private const val REQUEST_SMS_PERMISSIONS = 100
        private const val REQUEST_CONTACT_PERMISSIONS = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize DataStore and UserPreferences
        userPreferences = UserPreferences(dataStore)

        // Initialize defaults
        lifecycleScope.launch {
            userPreferences.initializeDefaults()
        }

        // Start the foreground service
        startForegroundService(Intent(this, SmsForwarderService::class.java))

        // Check for battery optimization exemption
        checkBatteryOptimization()

        // Request permissions
        requestPermissions()

        // Set the content view
        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                SettingsScreen(userPreferences)
            }
        }
    }

    private fun checkBatteryOptimization() {
        try {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e("BatteryOptimization", "Error checking battery optimization: ${e.message}")
        }
    }


//    private fun checkBatteryOptimization() {
//        val packageName = packageName
//        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
//
//        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
//            // If not ignoring battery optimizations, prompt the user
//            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
//                data = Uri.parse("package:$packageName")
//            }
//
//            // Show an explanation dialog to inform the user
//            AlertDialog.Builder(this)
//                .setTitle("Battery Optimization")
//                .setMessage("This app needs to run without battery optimizations to function properly. Please grant this permission.")
//                .setPositiveButton("Go to Settings") { _, _ ->
//                    try {
//                        startActivity(intent)
//                    } catch (e: ActivityNotFoundException) {
//                        // Handle the case where settings activity is not found
//                        Toast.makeText(this, "Unable to open settings.", Toast.LENGTH_SHORT).show()
//                    }
//                }
//                .setNegativeButton("Cancel", null)
//                .show()
//        }
//    }


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

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                REQUEST_SMS_PERMISSIONS
            )
        }
    }

}
