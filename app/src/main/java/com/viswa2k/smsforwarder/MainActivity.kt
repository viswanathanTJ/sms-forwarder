package com.viswa2k.smsforwarder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.viswa2k.smsforwarder.ui.nav.AppNavHost
import com.viswa2k.smsforwarder.ui.theme.SMSForwarderTheme
import com.viswa2k.smsforwarder.update.UpdateDialogHost

/** Single activity. Permissions are asked in onboarding; the privacy notice follows it (ui/nav/PrivacyNotice). */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
}
