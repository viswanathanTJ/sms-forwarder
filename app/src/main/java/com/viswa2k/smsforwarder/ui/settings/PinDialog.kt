package com.viswa2k.smsforwarder.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.viswa2k.smsforwarder.R
import com.viswa2k.smsforwarder.AppGraph
import com.viswa2k.smsforwarder.core.PinBackoff
import com.viswa2k.smsforwarder.core.PinLock
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide unlock state: one correct PIN unlocks until the app goes to the background. */
object AppLock {
    val unlocked = MutableStateFlow(false)
    @Volatile private var keepRequestedAt = 0L

    /** Called on the process ON_STOP; skipped when it follows a [keepForExternalScreen] within a few seconds. */
    fun lock() {
        val requested = keepRequestedAt
        keepRequestedAt = 0L
        if (android.os.SystemClock.elapsedRealtime() - requested > KEEP_WINDOW_MS) unlocked.value = false
    }

    /** The app is about to open its own picker / system screen (file picker, Fix): stay unlocked across it. */
    fun keepForExternalScreen() { keepRequestedAt = android.os.SystemClock.elapsedRealtime() }

    // ProcessLifecycleOwner reports ON_STOP ~0.7 s after the last activity stops.
    private const val KEEP_WINDOW_MS = 5_000L
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String, isError: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(6)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    )
}

/**
 * Asks for the current PIN and calls [onSuccess] when it matches. Wrong entries count towards
 * [PinBackoff] (persisted, so closing the dialog or the app does not reset it).
 */
@Composable
fun PinEntryDialog(
    title: String,
    onSuccess: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { AppGraph.from(context).settings }
    val record by settings.pinHash.collectAsStateWithLifecycle(initialValue = null)
    val attempts by remember { settings.pinAttempts.map { PinBackoff.decode(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val state = attempts
    val waitMs = state?.let { PinBackoff.remainingMs(it, now) } ?: 0L
    LaunchedEffect(state?.lockedUntil) {
        now = System.currentTimeMillis()
        while (state != null && PinBackoff.remainingMs(state, now) > 0) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val canSubmit = PinLock.isValid(pin) && record != null && state != null && waitMs == 0L && !busy
    val submit: () -> Unit = submit@{
        val stored = record ?: return@submit
        val current = state ?: return@submit
        if (!canSubmit) return@submit
        busy = true
        val entered = pin
        scope.launch {
            val ok = PinLock.verify(entered, stored)
            val at = System.currentTimeMillis()
            settings.setPinAttempts(PinBackoff.encode(if (ok) PinBackoff.onSuccess() else PinBackoff.onFailure(current, at)))
            busy = false
            if (ok) onSuccess() else { wrong = true; pin = ""; now = at }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(title) },
        text = {
            Column {
                PinField(pin, { pin = it; wrong = false }, stringResource(R.string.pin_label), isError = wrong)
                when {
                    waitMs > 0 -> Text(
                        stringResource(R.string.pin_wait, ((waitMs + 999) / 1000).toInt()),
                        color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp),
                    )
                    wrong -> Text(stringResource(R.string.pin_wrong), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = submit, enabled = canSubmit) { Text(stringResource(R.string.pin_unlock)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** New PIN + confirmation (4–6 digits). */
@Composable
fun SetPinDialog(onSet: (String) -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    val mismatch = again.length >= pin.length && again.isNotEmpty() && again != pin
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pin_set_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pin_set_body), style = MaterialTheme.typography.bodySmall)
                PinField(pin, { pin = it }, stringResource(R.string.pin_new))
                PinField(again, { again = it }, stringResource(R.string.pin_confirm), isError = mismatch)
                if (mismatch) Text(stringResource(R.string.pin_mismatch), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSet(pin) }, enabled = PinLock.isValid(pin) && pin == again) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Shown instead of Settings while a PIN is set and the app is locked. */
@Composable
fun LockedPane(onUnlock: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.pin_locked_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.pin_locked_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
        )
        Button(onClick = onUnlock) { Text(stringResource(R.string.pin_unlock)) }
    }
}
