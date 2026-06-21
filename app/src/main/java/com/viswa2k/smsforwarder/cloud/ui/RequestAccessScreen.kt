package com.viswa2k.smsforwarder.cloud.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Shown when a Google/email account is authenticated but not on the allow-list.
 * The user can submit an access request for the super-admin to approve.
 */
@Composable
fun RequestAccessScreen(vm: CloudViewModel) {
    val email by vm.email.collectAsState()
    val pending by vm.pendingRequest.collectAsState()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Account not approved", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            text = email ?: "",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))

        if (pending) {
            Text(
                "This email is not available for login yet. Your request has been sent — " +
                    "an administrator will review it. You'll be able to sign in once approved.",
                textAlign = TextAlign.Center,
            )
        } else {
            Text(
                "This email is not available for login. You can request access and an " +
                    "administrator will review it.",
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = { vm.requestAccess() }, modifier = Modifier.fillMaxWidth()) {
                Text("Request access")
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
            Text("Use a different account")
        }
    }
}
