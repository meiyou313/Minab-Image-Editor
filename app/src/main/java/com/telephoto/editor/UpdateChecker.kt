package com.telephoto.editor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.BuildConfig
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Checks GitHub Releases (meiyou313/TelePhoto-Editor) for newer versions of TelePhoto Editor.
 * Supports:
 * 1) Automatic background periodic checks via Android JobScheduler + System Notification
 * 2) In-app update notification banner & changelog dialog on launch
 * 3) Manual on-demand update checks triggered by the user
 */
object UpdateChecker {

    const val LATEST_RELEASE_API = "https://api.github.com/repos/meiyou313/TelePhoto-Editor/releases/latest"
    const val RELEASES_PAGE_URL = "https://github.com/meiyou313/TelePhoto-Editor/releases"

    const val EXTRA_OPEN_UPDATE_DIALOG = "extra_open_update_dialog"
    const val EXTRA_RELEASE_TAG = "extra_release_tag"
    const val EXTRA_RELEASE_TITLE = "extra_release_title"
    const val EXTRA_RELEASE_NOTES = "extra_release_notes"
    const val EXTRA_RELEASE_APK_URL = "extra_release_apk_url"
    const val EXTRA_RELEASE_PAGE_URL = "extra_release_page_url"

    private const val CHANNEL_ID = "telephoto_updates"
    private const val NOTIFICATION_ID = 31302
    private const val JOB_ID = 31301
    private const val PREFS_NAME = "telephoto_update_prefs"
    private const val KEY_LAST_NOTIFIED_TAG = "last_notified_tag"

    private val networkExecutor = Executors.newSingleThreadExecutor()

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val notes: String,
        val apkDownloadUrl: String?,
        val releasePageUrl: String,
        val publishedAt: String
    )

    sealed class CheckResult {
        data class UpdateAvailable(val release: ReleaseInfo) : CheckResult()
        data class UpToDate(val currentVersion: String, val latestVersion: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
    }

    /**
     * Compares two semantic version strings (e.g. "v1.2.1" vs "1.2.0").
     * Returns true strictly when [latestRaw] > [currentRaw].
     */
    fun isNewerVersion(latestRaw: String, currentRaw: String): Boolean {
        val latest = parseSemVer(latestRaw)
        val current = parseSemVer(currentRaw)
        val len = maxOf(latest.size, current.size)
        for (i in 0 until len) {
            val l = latest.getOrElse(i) { 0 }
            val c = current.getOrElse(i) { 0 }
            if (l != c) return l > c
        }
        return false
    }

    internal fun parseSemVer(raw: String): List<Int> {
        val cleaned = raw.trim().removePrefix("v").removePrefix("V")
            .substringBefore("-")
            .substringBefore("+")
        if (cleaned.isBlank()) return listOf(0)
        return cleaned.split(".").map { part ->
            part.filter { it.isDigit() }.toIntOrNull() ?: 0
        }
    }

    fun parseLatestReleaseJson(jsonText: String): ReleaseInfo? {
        if (jsonText.isBlank()) return null
        return try {
            val obj = JSONObject(jsonText)
            val tag = obj.optString("tag_name", "").trim()
            if (tag.isEmpty()) return null
            val version = tag.removePrefix("v").removePrefix("V")
            val name = obj.optString("name", "").ifBlank { "TelePhoto Editor $tag" }
            val body = obj.optString("body", "").trim()
            val htmlUrl = obj.optString("html_url", RELEASES_PAGE_URL).ifBlank { RELEASES_PAGE_URL }
            val publishedAt = obj.optString("published_at", "")

            var apkUrl: String? = null
            val assets = obj.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val assetName = asset.optString("name", "")
                    val dlUrl = asset.optString("browser_download_url", "")
                    if (assetName.endsWith(".apk", ignoreCase = true) && dlUrl.isNotBlank()) {
                        apkUrl = dlUrl
                        break
                    }
                }
            }

            ReleaseInfo(
                tagName = tag,
                versionName = version,
                title = name,
                notes = body,
                apkDownloadUrl = apkUrl,
                releasePageUrl = htmlUrl,
                publishedAt = publishedAt
            )
        } catch (t: Throwable) {
            FileLog.e("UpdateChecker parseLatestReleaseJson failed", t)
            null
        }
    }

    fun checkForUpdateSync(currentVersion: String = BuildConfig.VERSION_NAME): CheckResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "TelePhoto-Editor-Android/$currentVersion")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                return CheckResult.Error("GitHub API HTTP $code")
            }
            val body = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val release = parseLatestReleaseJson(body)
                ?: return CheckResult.Error("Invalid release metadata")

            if (isNewerVersion(release.versionName, currentVersion)) {
                CheckResult.UpdateAvailable(release)
            } else {
                CheckResult.UpToDate(currentVersion, release.versionName)
            }
        } catch (t: Throwable) {
            CheckResult.Error(t.message ?: t.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    fun checkForUpdateAsync(
        context: Context,
        postSystemNotification: Boolean,
        forceNotificationRepeat: Boolean = false,
        onResult: (CheckResult) -> Unit
    ) {
        val appCtx = context.applicationContext
        networkExecutor.execute {
            val result = checkForUpdateSync()
            if (result is CheckResult.UpdateAvailable && postSystemNotification) {
                maybeShowUpdateNotification(appCtx, result.release, force = forceNotificationRepeat)
            }
            AndroidUtilities.runOnUIThread {
                onResult(result)
            }
        }
    }

    fun schedulePeriodicCheck(context: Context) {
        try {
            val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
            val existing = scheduler.allPendingJobs.any { it.id == JOB_ID }
            if (existing) return

            val component = ComponentName(context, UpdateCheckJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(12L * 60L * 60L * 1000L) // Check every 12 hours
                .setPersisted(false)
                .build()
            scheduler.schedule(info)
        } catch (t: Throwable) {
            FileLog.e("UpdateChecker.schedulePeriodicCheck failed", t)
        }
    }

    fun maybeShowUpdateNotification(context: Context, release: ReleaseInfo, force: Boolean = false) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastNotified = prefs.getString(KEY_LAST_NOTIFIED_TAG, null)
        if (!force && lastNotified == release.tagName) {
            return
        }
        if (showUpdateNotification(context, release)) {
            prefs.edit().putString(KEY_LAST_NOTIFIED_TAG, release.tagName).apply()
        }
    }

    fun showUpdateNotification(context: Context, release: ReleaseInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        ensureNotificationChannel(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
            putExtra(EXTRA_RELEASE_TAG, release.tagName)
            putExtra(EXTRA_RELEASE_TITLE, release.title)
            putExtra(EXTRA_RELEASE_NOTES, release.notes)
            putExtra(EXTRA_RELEASE_APK_URL, release.apkDownloadUrl)
            putExtra(EXTRA_RELEASE_PAGE_URL, release.releasePageUrl)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val contentPi = PendingIntent.getActivity(context, 100, openAppIntent, flags)

        val downloadIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(release.apkDownloadUrl ?: release.releasePageUrl)
        )
        val downloadPi = PendingIntent.getActivity(context, 101, downloadIntent, flags)

        val shortBody = "نسخه جدید ${release.tagName} منتشر شد. برای مشاهده تغییرات و دانلود لمس کنید."
        val bigText = buildString {
            append(shortBody)
            if (release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes.take(400))
            }
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tg_update)
            .setContentTitle("آپدیت جدید TelePhoto Editor (${release.tagName})")
            .setContentText(shortBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPi)
            .addAction(R.drawable.ic_tg_update, "دانلود APK", downloadPi)
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
            true
        } catch (t: Throwable) {
            FileLog.e("UpdateChecker.showUpdateNotification failed", t)
            false
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "App Updates",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Notifies when a new TelePhoto Editor release is published on GitHub."
                }
                mgr.createNotificationChannel(channel)
            }
        }
    }
}
