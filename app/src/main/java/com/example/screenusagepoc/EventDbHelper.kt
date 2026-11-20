package com.example.screenusagepoc

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScreenEvent(
    val id: Long,
    val timestamp: Long,
    val eventType: String,
    val details: String
)

data class AppSession(
    val id: Long,
    val packageName: String,
    val appName: String,
    val startTime: Long,
    val endTime: Long,
    val durationMs: Long,
    val date: String
)

data class AppUsageSummary(
    val packageName: String,
    val appName: String,
    val totalDurationMs: Long,
    val sessionCount: Int
)

class EventDbHelper(context: Context) : SQLiteOpenHelper(context, "screen_poc.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS screen_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                details TEXT
            )
            """.trimIndent()
        )
        createAppSessionsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createAppSessionsTable(db)
        }
    }

    private fun createAppSessionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS app_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                package_name TEXT NOT NULL,
                app_name TEXT NOT NULL,
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                date TEXT NOT NULL,
                UNIQUE(package_name, start_time, end_time) ON CONFLICT IGNORE
            )
            """.trimIndent()
        )
    }

    // --- Screen Events ---

    fun logEvent(eventType: String, details: String = "") {
        val values = ContentValues().apply {
            put("timestamp", System.currentTimeMillis())
            put("event_type", eventType)
            put("details", details)
        }
        writableDatabase.insert("screen_events", null, values)
    }

    fun getEventCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM screen_events", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getRecentScreenEvents(limit: Int = 50): List<ScreenEvent> {
        val list = mutableListOf<ScreenEvent>()
        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, event_type, details FROM screen_events ORDER BY timestamp DESC LIMIT ?",
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    ScreenEvent(
                        id = it.getLong(0),
                        timestamp = it.getLong(1),
                        eventType = it.getString(2),
                        details = it.getString(3) ?: ""
                    )
                )
            }
        }
        return list
    }

    // --- App Sessions ---

    fun insertAppSession(
        packageName: String,
        appName: String,
        startTime: Long,
        endTime: Long,
        durationMs: Long,
        date: String
    ): Long {
        val values = ContentValues().apply {
            put("package_name", packageName)
            put("app_name", appName)
            put("start_time", startTime)
            put("end_time", endTime)
            put("duration_ms", durationMs)
            put("date", date)
        }
        return writableDatabase.insertWithOnConflict("app_sessions", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun getAppSessionCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM app_sessions", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun getRecentAppSessions(limit: Int = 100): List<AppSession> {
        val list = mutableListOf<AppSession>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_name, start_time, end_time, duration_ms, date
            FROM app_sessions
            ORDER BY start_time DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(limit.toString())
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    AppSession(
                        id = it.getLong(0),
                        packageName = it.getString(1),
                        appName = it.getString(2),
                        startTime = it.getLong(3),
                        endTime = it.getLong(4),
                        durationMs = it.getLong(5),
                        date = it.getString(6)
                    )
                )
            }
        }
        return list
    }

    fun getTodayAppUsageSummaries(todayDate: String): List<AppUsageSummary> {
        val list = mutableListOf<AppUsageSummary>()
        val cursor = readableDatabase.rawQuery(
            """
            SELECT package_name, app_name, SUM(duration_ms) as total_duration, COUNT(*) as session_count
            FROM app_sessions
            WHERE date = ?
            GROUP BY package_name, app_name
            ORDER BY total_duration DESC
            """.trimIndent(),
            arrayOf(todayDate)
        )
        cursor.use {
            while (it.moveToNext()) {
                list.add(
                    AppUsageSummary(
                        packageName = it.getString(0),
                        appName = it.getString(1),
                        totalDurationMs = it.getLong(2),
                        sessionCount = it.getInt(3)
                    )
                )
            }
        }
        return list
    }

    // --- CSV Exports ---

    fun exportToCsv(outputDir: File): File {
        return exportScreenEventsCsv(outputDir)
    }

    fun exportScreenEventsCsv(outputDir: File): File {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val fileName = "screen_events_${System.currentTimeMillis()}.csv"
        val file = File(outputDir, fileName)

        val cursor = readableDatabase.rawQuery(
            "SELECT id, timestamp, event_type, details FROM screen_events ORDER BY timestamp ASC",
            null
        )

        file.bufferedWriter().use { writer ->
            writer.write("id,timestamp_ms,datetime,event_type,details\n")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val ts = it.getLong(1)
                    val formattedDate = dateFormat.format(Date(ts))
                    val type = it.getString(2)
                    val details = it.getString(3)?.replace(",", ";") ?: ""
                    writer.write("$id,$ts,$formattedDate,$type,$details\n")
                }
            }
        }
        return file
    }

    fun exportAppSessionsCsv(outputDir: File): File {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val fileName = "app_sessions_${System.currentTimeMillis()}.csv"
        val file = File(outputDir, fileName)

        val cursor = readableDatabase.rawQuery(
            """
            SELECT id, package_name, app_name, start_time, end_time, duration_ms, date
            FROM app_sessions
            ORDER BY start_time ASC
            """.trimIndent(),
            null
        )

        file.bufferedWriter().use { writer ->
            writer.write("id,package_name,app_name,start_time_ms,start_datetime,end_time_ms,end_datetime,duration_ms,duration_seconds,duration_formatted,date\n")
            cursor.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val pkg = it.getString(1)
                    val name = it.getString(2).replace(",", ";")
                    val startTs = it.getLong(3)
                    val endTs = it.getLong(4)
                    val durationMs = it.getLong(5)
                    val date = it.getString(6)

                    val startFormatted = dateFormat.format(Date(startTs))
                    val endFormatted = dateFormat.format(Date(endTs))
                    val durationSec = durationMs / 1000.0
                    val durationStr = AppUsageTracker.formatDuration(durationMs)

                    writer.write("$id,$pkg,$name,$startTs,$startFormatted,$endTs,$endFormatted,$durationMs,$durationSec,$durationStr,$date\n")
                }
            }
        }
        return file
    }
}
