package com.viswa2k.smsforwarder.cloud.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Downloads the release APK with [DownloadManager] and launches the system
 * package installer when the download completes. The OS handles the
 * "install unknown apps" consent screen.
 */
object UpdateInstaller {
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** True if the app may install packages (always true below API 26). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /** Send the user to the "install unknown apps" settings screen for this app. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Enqueue the APK download; on completion, fire the install intent.
     * If install permission is missing, routes the user to grant it first.
     */
    fun startUpdate(context: Context, info: UpdateInfo) {
        val appContext = context.applicationContext
        if (!canInstall(appContext)) {
            Toast.makeText(appContext, "Allow installing apps, then tap Install again", Toast.LENGTH_LONG).show()
            requestInstallPermission(context)
            return
        }

        val dm = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(info.apkUrl))
            .setTitle("SMS Forwarder ${info.version}")
            .setDescription("Downloading update…")
            .setMimeType(APK_MIME)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, info.apkName)

        val downloadId = dm.enqueue(request)
        Toast.makeText(appContext, "Downloading update…", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != downloadId) return
                try {
                    val uri = dm.getUriForDownloadedFile(downloadId)
                    if (uri == null) {
                        Toast.makeText(appContext, "Update download failed", Toast.LENGTH_LONG).show()
                    } else {
                        val install = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, APK_MIME)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        appContext.startActivity(install)
                    }
                } catch (e: Exception) {
                    Log.e("UpdateInstaller", "Install launch failed", e)
                } finally {
                    runCatching { appContext.unregisterReceiver(this) }
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED, // system broadcast; must be exported on API 33+
        )
    }
}
