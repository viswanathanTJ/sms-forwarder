package com.viswa2k.smsforwarder.cloud.data

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.viswa2k.smsforwarder.BuildConfig

object FirebaseProvider {
    // The device reaches the host's emulator suite via `adb reverse` (tcp:8080 and tcp:9099),
    // which tunnels the device's own loopback to the host — works on Genymotion too.
    private const val EMULATOR_HOST = "127.0.0.1"
    @Volatile private var configured = false

    private fun configureEmulatorOnce() {
        if (configured || !BuildConfig.USE_EMULATOR) return
        synchronized(this) {
            if (configured) return
            FirebaseAuth.getInstance().useEmulator(EMULATOR_HOST, 9099)
            FirebaseFirestore.getInstance().useEmulator(EMULATOR_HOST, 8080)
            configured = true
        }
    }

    val auth: FirebaseAuth get() { configureEmulatorOnce(); return FirebaseAuth.getInstance() }
    val db: FirebaseFirestore get() { configureEmulatorOnce(); return FirebaseFirestore.getInstance() }
}
