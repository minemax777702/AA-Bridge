package dev.caraalink.update

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import dev.caraalink.BuildConfig
import dev.caraalink.diagnostics.DiagnosticLogger
import dev.caraalink.settings.AppSettings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub's latest tagged Release for something newer than this install,
 * opens the release page for manual review and installation.
 */
object UpdateChecker {

    private const val OWNER = "minemax777702"
    private const val REPO = "AA-Bridge"
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4

    private data class UpdateInfo(val title: String, val htmlUrl: String)

    private fun logger(context: Context) = DiagnosticLogger(context, "update-checker")

    /** Called from MainActivity.onCreate() -- silent unless something newer actually exists. */
    fun checkIfDue(context: Context) {
        if (!AppSettings.updateAutoCheck(context)) return
        checkNow(context, manual = false)
    }

    /** [manual] controls whether a "you're up to date" / error Toast shows; a found update always prompts. */
    fun checkNow(context: Context, manual: Boolean) {
        Thread({
            val result = runCatching { checkLatestRelease() }
            val outcome = result.getOrNull()
            logger(context).log("update check: ${result.exceptionOrNull() ?: outcome ?: "up to date"}")
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                when {
                    result.isFailure -> if (manual) toast(context, "Update check failed: ${result.exceptionOrNull()?.message}")
                    outcome != null -> promptUpdate(context, outcome)
                    manual -> toast(context, "Already on the latest release")
                }
            }
        }, "UpdateChecker").start()
    }

    /**
     * Returns info for a newer tagged release, else null. `/releases/latest`
     * is a hard 404 (not an empty list) when the repo has never had a single
     * Release published. That's "nothing to compare against", not a
     * failure -- treated as up to date, no error surfaced.
     */
    private fun checkLatestRelease(): UpdateInfo? {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases/latest") ?: return null
        val json = JSONObject(body)
        val tag = json.getString("tag_name").removePrefix("v")
        if (!isNewerVersion(tag, BuildConfig.VERSION_NAME)) return null
        return UpdateInfo("Update available: v$tag", "https://github.com/$OWNER/$REPO/releases/tag/v$tag")
    }

    /** Accept only stable numeric release versions; reject older or malformed tags. */
    internal fun isNewerVersion(candidate: String, current: String): Boolean {
        fun parse(version: String): List<Int>? {
            val parts = version.split('.')
            if (parts.size != 3 || parts.any { it.isEmpty() || !it.all { ch -> ch in '0'..'9' } }) return null
            return parts.map { it.toIntOrNull() ?: return null }
        }
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (i in next.indices) {
            if (next[i] != installed[i]) return next[i] > installed[i]
        }
        return false
    }

    /** null on a 404 (nothing there to compare against); throws on any other non-2xx. */
    private fun get(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            when (conn.responseCode) {
                in 200..299 -> conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                404 -> null
                else -> error("HTTP ${conn.responseCode}")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(context: Context, text: String) =
        Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    /**
     * Shows a popup with an Open GitHub button when called while the app is in
     * the foreground (the only caller, MainActivity, always passes itself as
     * an Activity context); also raises a notification covering the case
     * where the check landed while the user wasn't looking at the dialog
     * (e.g. they dismissed it, or the screen was off during the auto-check).
     */
    private fun promptUpdate(context: Context, info: UpdateInfo) {
        notify(context, info)
        val activity = context as? android.app.Activity ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(info.title)
            .setMessage(
                "Open the release page on GitHub? Review and download the APK manually.",
            )
            .setPositiveButton("Open GitHub") { _, _ ->
                openPage(context, info.htmlUrl)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun openPage(context: Context, url: String) =
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun notify(context: Context, info: UpdateInfo) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        // Tapping the notification re-opens the app rather than a browser --
        // MainActivity.onCreate() runs checkIfDue() again, which re-shows the
        // download dialog since the same newer version is still out there.
        val intent = Intent(context, context.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(dev.caraalink.R.drawable.ic_stat_bridge)
            .setContentTitle(info.title)
            .setSubText(context.getString(dev.caraalink.R.string.made_by))
            .setContentText("Tap to review the release")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
