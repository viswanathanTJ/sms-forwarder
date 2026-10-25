package com.viswa2k.smsforwarder

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.viswa2k.smsforwarder.ui.nav.AppNavHost
import com.viswa2k.smsforwarder.ui.theme.SMSForwarderTheme
import com.viswa2k.smsforwarder.update.UpdateDialogHost

/** Single activity. Permission prompting moves to onboarding (Task 15). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Show the privacy policy only until the user acknowledges it once.
        if (!isPrivacyAccepted()) showPrivacyPolicy()

        setContent {
            SMSForwarderTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    AppNavHost()
                    // Checks GitHub Releases on launch; prompts to install if a newer APK exists.
                    UpdateDialogHost()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        (application as ForwarderApp).ensureRunning()
    }

    private fun isPrivacyAccepted(): Boolean =
        getSharedPreferences("app_prefs", Context.MODE_PRIVATE).getBoolean("privacy_accepted", false)

    private fun showPrivacyPolicy() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.privacy_policy_title))
            .setMessage(getString(R.string.privacy_policy_summary))
            .setPositiveButton(getString(R.string.privacy_policy_accept)) { dialog, _ ->
                getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("privacy_accepted", true).apply()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
