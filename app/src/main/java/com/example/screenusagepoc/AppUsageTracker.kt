package com.example.screenusagepoc

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object AppUsageTracker {

    private val appNameCache = ConcurrentHashMap<String, String>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun syncUsage(context: Context, dbHelper: EventDbHelper) {
        if (!hasUsageStatsPermission(context)) return

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val prefs = context.getSharedPreferences("screen_usage_poc_prefs", Context.MODE_PRIVATE)

        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val defaultStart = calendar.timeInMillis
        val lastSync = prefs.getLong("last_usage_sync_time", defaultStart)
        val queryStart = maxOf(lastSync - 5000, now - (24 * 60 * 60 * 1000))

        val events = usageStatsManager.queryEvents(queryStart, now) ?: return
        val event = UsageEvents.Event()

        var currentPackage: String? = null
        var currentStart: Long = 0
        var latestEventTime = queryStart

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val eventType = event.eventType
            val pkg = event.packageName ?: continue
            val ts = event.timeStamp

            if (ts > latestEventTime) {
                latestEventTime = ts
            }

            when (eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED,
                @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    if (currentPackage != null && currentPackage != pkg && currentStart > 0) {
                        val duration = ts - currentStart
                        if (duration >= 1000) {
                            saveSession(context, dbHelper, currentPackage!!, currentStart, ts, duration)
                        }
                    }
                    currentPackage = pkg
                    currentStart = ts
                }

                UsageEvents.Event.ACTIVITY_PAUSED,
                @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (currentPackage == pkg && currentStart > 0) {
                        val duration = ts - currentStart
                        if (duration >= 1000) {
                            saveSession(context, dbHelper, pkg, currentStart, ts, duration)
                        }
                        currentPackage = null
                        currentStart = 0
                    }
                }

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {
                    if (currentPackage != null && currentStart > 0) {
                        val duration = ts - currentStart
                        if (duration >= 1000) {
                            saveSession(context, dbHelper, currentPackage!!, currentStart, ts, duration)
                        }
                        currentPackage = null
                        currentStart = 0
                    }
                }
            }
        }

        val nextSyncTime = if (currentPackage != null && currentStart > 0) currentStart else latestEventTime
        prefs.edit().putLong("last_usage_sync_time", nextSyncTime).apply()
    }

    private fun saveSession(
        context: Context,
        dbHelper: EventDbHelper,
        packageName: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long
    ) {
        val appName = getAppLabel(context, packageName)
        val date = dateFormat.format(Date(startTime))
        dbHelper.insertAppSession(
            packageName = packageName,
            appName = appName,
            startTime = startTime,
            endTime = endTime,
            durationMs = durationMs,
            date = date
        )
    }

    fun getAppLabel(context: Context, packageName: String): String {
        return appNameCache.getOrPut(packageName) {
            try {
                val pm = context.packageManager
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    pm.getApplicationInfo(packageName, 0)
                }
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                val parts = packageName.split('.')
                if (parts.size >= 2 && parts.last().equals("android", ignoreCase = true)) {
                    parts[parts.size - 2].replaceFirstChar { it.uppercase() }
                } else {
                    parts.last().replaceFirstChar { it.uppercase() }
                }
            }
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> String.format(Locale.getDefault(), "%02dh %02dm %02ds", hours, minutes, seconds)
            minutes > 0 -> String.format(Locale.getDefault(), "%02dm %02ds", minutes, seconds)
            else -> String.format(Locale.getDefault(), "%ds", seconds)
        }
    }
}
