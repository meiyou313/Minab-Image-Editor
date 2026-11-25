package com.telephoto.editor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.telegram.messenger.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Persistent crash, OpenGL, and engine error recorder with breadcrumb trail.
 * Captures uncaught exceptions on ALL threads (UI, OpenGL, DispatchQueue, IO) and stores
 * structured JSON reports safely on disk so users can view and copy them in the Debug Console.
 */
object CrashLogManager {

    const val LEVEL_CRASH = "CRASH"
    const val LEVEL_ERROR = "ERROR"
    const val LEVEL_GL = "GL"
    const val LEVEL_INFO = "INFO"

    private const val PREFS_NAME = "telephoto_debug_prefs"
    private const val KEY_UNREAD_CRASH = "unread_crash_count"
    private const val MAX_ENTRIES = 60
    private const val MAX_BREADCRUMBS = 25

    data class DebugEntry(
        val id: String,
        val timestampMs: Long,
        val timestampText: String,
        val level: String,
        val threadName: String,
        val tag: String,
        val title: String,
        val stackTrace: String,
        val breadcrumbs: List<String>,
        val appVersion: String,
        val deviceSummary: String
    )

    private val lock = Any()
    private val breadcrumbs = ArrayDeque<String>(MAX_BREADCRUMBS + 2)
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var installed = false
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        synchronized(lock) {
            if (installed) return
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    recordEntrySync(
                        level = LEVEL_CRASH,
                        threadName = thread.name,
                        tag = "UncaughtException",
                        title = formatTitle(null, throwable),
                        stackTrace = formatStackTrace(throwable),
                        markUnread = true
                    )
                } catch (_: Throwable) {
                    // Never throw inside crash handler
                }
                previousHandler?.uncaughtException(thread, throwable)
            }
            installed = true
        }
        addBreadcrumb("App started (v${safeVersionName()} / code ${safeVersionCode()})")
    }

    @JvmStatic
    fun addBreadcrumb(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        synchronized(lock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) {
                breadcrumbs.removeFirst()
            }
            breadcrumbs.addLast("[$stamp] $message")
        }
    }

    fun getRecentBreadcrumbs(): List<String> = synchronized(lock) { breadcrumbs.toList() }

    @JvmStatic
    fun recordError(tag: String, message: String?, throwable: Throwable?) {
        val level = if (tag.contains("GL", ignoreCase = true) ||
            (message?.contains("shader", ignoreCase = true) == true) ||
            (message?.contains("egl", ignoreCase = true) == true)
        ) LEVEL_GL else LEVEL_ERROR

        recordEntrySync(
            level = level,
            threadName = Thread.currentThread().name,
            tag = tag,
            title = formatTitle(message, throwable),
            stackTrace = if (throwable != null) formatStackTrace(throwable) else (message ?: ""),
            markUnread = false
        )
    }

    @JvmStatic
    fun recordInfo(tag: String, message: String) {
        addBreadcrumb("$tag: $message")
    }

    fun hasUnreadCrash(): Boolean = getUnreadCrashCount() > 0

    fun getUnreadCrashCount(): Int {
        val ctx = appContext ?: return 0
        return try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getInt(KEY_UNREAD_CRASH, 0)
        } catch (_: Throwable) {
            0
        }
    }

    fun markCrashesRead() {
        val ctx = appContext ?: return
        try {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_UNREAD_CRASH, 0).apply()
        } catch (_: Throwable) {
        }
    }

    fun getAllEntries(): List<DebugEntry> {
        synchronized(lock) {
            return readEntriesLocked()
        }
    }

    fun clearAll() {
        synchronized(lock) {
            val file = logFile() ?: return
            try {
                if (file.exists()) file.delete()
            } catch (_: Throwable) {
            }
            markCrashesRead()
        }
    }

    fun formatEntryForCopy(entry: DebugEntry): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("[${entry.level}] ${entry.title}")
        sb.appendLine("Time    : ${entry.timestampText}")
        sb.appendLine("Thread  : ${entry.threadName}")
        sb.appendLine("Tag     : ${entry.tag}")
        sb.appendLine("App     : ${entry.appVersion}")
        sb.appendLine("Device  : ${entry.deviceSummary}")
        if (entry.breadcrumbs.isNotEmpty()) {
            sb.appendLine("--- Recent Actions ---")
            entry.breadcrumbs.takeLast(12).forEach { sb.appendLine("  $it") }
        }
        if (entry.stackTrace.isNotBlank()) {
            sb.appendLine("--- Details / Stack Trace ---")
            sb.appendLine(entry.stackTrace.trim())
        }
        sb.appendLine("========================================")
        return sb.toString()
    }

    fun formatFullReport(context: Context? = appContext): String {
        val entries = getAllEntries()
        val sb = StringBuilder()
        sb.appendLine("TelePhoto Editor — Debug Diagnostic Report")
        sb.appendLine("Generated : ${formatTimestamp(System.currentTimeMillis())}")
        sb.appendLine("App       : v${safeVersionName()} (code ${safeVersionCode()})")
        sb.appendLine("Device    : ${buildDeviceSummary()}")
        if (context != null) {
            sb.appendLine("Cert SHA256: ${getSigningCertFingerprint(context)}")
        }
        sb.appendLine("Total Logs: ${entries.size} (Crashes: ${entries.count { it.level == LEVEL_CRASH }})")
        sb.appendLine()

        val crumbs = getRecentBreadcrumbs()
        if (crumbs.isNotEmpty()) {
            sb.appendLine("--- Current Session Breadcrumbs ---")
            crumbs.forEach { sb.appendLine("  $it") }
            sb.appendLine()
        }

        if (entries.isEmpty()) {
            sb.appendLine("No crashes or engine errors recorded.")
        } else {
            entries.forEach { entry ->
                sb.appendLine(formatEntryForCopy(entry))
            }
        }
        return sb.toString()
    }

    fun getSigningCertFingerprint(context: Context): String {
        return try {
            val pm = context.packageManager
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                if (signingInfo == null) null
                else if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
                else signingInfo.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            val certBytes = signatures?.firstOrNull()?.toByteArray() ?: return "Unavailable"
            val digest = MessageDigest.getInstance("SHA-256").digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }.take(35) + "..."
        } catch (_: Throwable) {
            "Unavailable"
        }
    }

    fun buildDeviceSummary(): String {
        val brand = Build.MANUFACTURER ?: "Unknown"
        val model = Build.MODEL ?: "Unknown"
        val release = Build.VERSION.RELEASE ?: "?"
        val sdk = Build.VERSION.SDK_INT
        val abi = Build.SUPPORTED_ABIS?.firstOrNull() ?: "unknown"
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return "$brand $model • Android $release (API $sdk) • $abi • Heap ${usedMb}/${maxMb}MB"
    }

    internal fun serializeEntries(entries: List<DebugEntry>): String {
        val arr = JSONArray()
        for (e in entries) {
            val obj = JSONObject()
            obj.put("id", e.id)
            obj.put("ts", e.timestampMs)
            obj.put("tsText", e.timestampText)
            obj.put("level", e.level)
            obj.put("thread", e.threadName)
            obj.put("tag", e.tag)
            obj.put("title", e.title)
            obj.put("trace", e.stackTrace)
            obj.put("app", e.appVersion)
            obj.put("device", e.deviceSummary)
            val crumbsArr = JSONArray()
            e.breadcrumbs.forEach { crumbsArr.put(it) }
            obj.put("crumbs", crumbsArr)
            arr.put(obj)
        }
        return arr.toString()
    }

    internal fun deserializeEntries(json: String): List<DebugEntry> {
        if (json.isBlank()) return emptyList()
        val result = mutableListOf<DebugEntry>()
        try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val crumbsJson = obj.optJSONArray("crumbs")
                val crumbs = mutableListOf<String>()
                if (crumbsJson != null) {
                    for (j in 0 until crumbsJson.length()) {
                        crumbs.add(crumbsJson.optString(j))
                    }
                }
                result.add(
                    DebugEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestampMs = obj.optLong("ts", 0L),
                        timestampText = obj.optString("tsText", ""),
                        level = obj.optString("level", LEVEL_ERROR),
                        threadName = obj.optString("thread", "unknown"),
                        tag = obj.optString("tag", "Engine"),
                        title = obj.optString("title", "Unknown Error"),
                        stackTrace = obj.optString("trace", ""),
                        breadcrumbs = crumbs,
                        appVersion = obj.optString("app", ""),
                        deviceSummary = obj.optString("device", "")
                    )
                )
            }
        } catch (_: Throwable) {
        }
        return result
    }

    private fun recordEntrySync(
        level: String,
        threadName: String,
        tag: String,
        title: String,
        stackTrace: String,
        markUnread: Boolean
    ) {
        val ctx = appContext ?: return
        synchronized(lock) {
            try {
                val now = System.currentTimeMillis()
                val entry = DebugEntry(
                    id = UUID.randomUUID().toString().take(8),
                    timestampMs = now,
                    timestampText = formatTimestamp(now),
                    level = level,
                    threadName = threadName,
                    tag = tag,
                    title = title.take(280),
                    stackTrace = stackTrace.take(8000),
                    breadcrumbs = breadcrumbs.toList(),
                    appVersion = "v${safeVersionName()} (${safeVersionCode()})",
                    deviceSummary = buildDeviceSummary()
                )
                val existing = readEntriesLocked().toMutableList()
                existing.add(0, entry)
                val trimmed = if (existing.size > MAX_ENTRIES) existing.subList(0, MAX_ENTRIES) else existing
                writeEntriesLocked(trimmed)

                if (markUnread) {
                    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val cur = prefs.getInt(KEY_UNREAD_CRASH, 0)
                    // commit() synchronously so it survives immediate process death
                    prefs.edit().putInt(KEY_UNREAD_CRASH, cur + 1).commit()
                }
            } catch (t: Throwable) {
                Log.e("CrashLogManager", "Failed to save debug entry", t)
            }
        }
    }

    private fun logFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.filesDir, "debug_logs")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "events.json")
    }

    private fun readEntriesLocked(): List<DebugEntry> {
        val file = logFile() ?: return emptyList()
        if (!file.exists()) return emptyList()
        return try {
            deserializeEntries(file.readText(Charsets.UTF_8))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun writeEntriesLocked(entries: List<DebugEntry>) {
        val file = logFile() ?: return
        val tmp = File(file.parentFile, "events.json.tmp")
        tmp.writeText(serializeEntries(entries), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.writeText(serializeEntries(entries), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun formatTitle(message: String?, throwable: Throwable?): String {
        return when {
            throwable != null && !message.isNullOrBlank() ->
                "$message — ${throwable.javaClass.simpleName}: ${throwable.message ?: ""}".trim()
            throwable != null ->
                "${throwable.javaClass.simpleName}: ${throwable.message ?: "(no message)"}"
            !message.isNullOrBlank() -> message
            else -> "Unknown Error"
        }
    }

    private fun formatStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun formatTimestamp(ms: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    }

    private fun safeVersionName(): String = try {
        BuildConfig.VERSION_NAME
    } catch (_: Throwable) {
        "1.2.0"
    }

    private fun safeVersionCode(): Int = try {
        BuildConfig.VERSION_CODE
    } catch (_: Throwable) {
        5
    }
}
