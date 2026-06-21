package com.viswa2k.smsforwarder.cloud.ui

import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.viswa2k.smsforwarder.BuildConfig
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

@Composable
fun SignInScreen(vm: CloudViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var signUpMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(if (signUpMode) "Create Cloud SMS account" else "Cloud SMS Sign-in", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        if (signUpMode) {
            Spacer(Modifier.height(4.dp))
            Text(
                "After creating your account you'll request access; an admin must approve it before you can read messages.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                busy = true; error = null
                val onErr: (String) -> Unit = { busy = false; error = it }
                if (signUpMode) vm.signUpEmail(email.trim(), password, onErr)
                else vm.signInEmail(email.trim(), password, onErr)
            },
            enabled = !busy && email.isNotBlank() && password.length >= 6,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (signUpMode) "Create account" else "Sign in") }
        Spacer(Modifier.height(4.dp))
        TextButton(onClick = { error = null; signUpMode = !signUpMode }, enabled = !busy) {
            Text(if (signUpMode) "Have an account? Sign in" else "New here? Create an account")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                busy = true; error = null
                scope.launch {
                    try {
                        // Explicit "Sign in with Google" button flow: always opens the account
                        // picker / "add account" UI (unlike GetGoogleIdOption, which throws
                        // NoCredentialException when nothing is pre-authorized).
                        val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .setNonce(sha256(randomNonce()))
                            .build()
                        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                        val result = CredentialManager.create(context).getCredential(context, request)
                        val cred = GoogleIdTokenCredential.createFrom(result.credential.data)
                        vm.signInGoogle(cred.idToken) { busy = false; error = it }
                    } catch (e: GetCredentialCancellationException) {
                        busy = false // user dismissed the picker — no error
                    } catch (e: NoCredentialException) {
                        busy = false
                        error = "No Google account on this device. Add one in Settings → Accounts, then retry."
                    } catch (e: Exception) {
                        busy = false
                        error = e.message ?: "Google sign-in failed"
                    }
                }
            },
            enabled = !busy, modifier = Modifier.fillMaxWidth(),
        ) { Text("Sign in with Google") }
    }
}

private fun randomNonce(): String {
    val b = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
private fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
