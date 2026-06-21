package com.viswa2k.smsforwarder

import android.Manifest
import android.app.AlertDialog
import android.app.AlarmManager
import android.app.PendingIntent
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
import com.viswa2k.smsforwarder.ui.SettingsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

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

        // Show privacy policy before anything else
        showPrivacyPolicy()

        // Initialize defaults
        lifecycleScope.launch {
            userPreferences.initializeDefaults()
            checkAndStartService()
            try {
                if (userPreferences.isCloudChannelEnabled.first()) {
                    com.viswa2k.smsforwarder.cloud.data.SmsCloudUploader(applicationContext).flushQueue()
                }
            } catch (e: Exception) { Log.e("MainActivity", "queue flush failed", e) }
        }

        // Check for battery optimization exemption
        checkBatteryOptimization()

        // Request permissions with clear explanations
        requestPermissions()

        // Set the content view
        setContent {
            MaterialTheme {
                Surface {
                    val cloudVm: com.viswa2k.smsforwarder.cloud.ui.CloudViewModel =
                        androidx.lifecycle.viewmodel.compose.viewModel()
                    val nav = rememberNavController()
                    NavHost(nav, startDestination = "settings") {
                        composable("settings") {
                            SettingsScreen(userPreferences, onOpenCloud = { nav.navigate("cloud") })
                        }
                        composable("cloud") {
                            com.viswa2k.smsforwarder.cloud.ui.CloudNav(cloudVm)
                        }
                    }
                    // Checks GitHub Releases on launch; prompts to install if a newer APK exists.
                    com.viswa2k.smsforwarder.cloud.update.UpdateDialogHost()
                }
            }
        }
    }

    private fun showPrivacyPolicy() {
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_policy_title))
            .setMessage(getString(R.string.privacy_policy_summary))
            .setPositiveButton("I Understand") { dialog, _ ->
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
            } else {
                // Schedule periodic battery optimization check
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val checkBatteryIntent = Intent(this, ServiceRestartReceiver::class.java).apply {
                    action = "${packageName}.RESTART_SERVICE"
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    this,
                    1,
                    checkBatteryIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )

                // Check every hour to be more battery efficient
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR,
                    AlarmManager.INTERVAL_HOUR,
                    pendingIntent
                )
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
        val smsForwardServiceEnabled = userPreferences.isSmsForwarderService.first()

        val serviceIntent = Intent(this, SmsForwarderService::class.java).apply {
            putExtra("SMS_FORWARD_SERVICE_ENABLED", smsForwardServiceEnabled)
        }

        if (smsForwardServiceEnabled) {
            ContextCompat.startForegroundService(this, serviceIntent) // Start the service
        } else {
            this.stopService(serviceIntent) // Stop the service
        }
    }

}
