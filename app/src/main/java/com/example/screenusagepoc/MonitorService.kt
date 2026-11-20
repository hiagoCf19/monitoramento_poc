package com.example.screenusagepoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class MonitorService : Service() {

    companion object {
        var isRunning: Boolean = false
            private set
    }

    private lateinit var dbHelper: EventDbHelper
    private val handler = Handler(Looper.getMainLooper())

    private val periodicSyncRunnable = object : Runnable {
        override fun run() {
            AppUsageTracker.syncUsage(this@MonitorService, dbHelper)
            handler.postDelayed(this, 30_000)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    dbHelper.logEvent("SCREEN_ON", "Tela acendeu")
                    AppUsageTracker.syncUsage(this@MonitorService, dbHelper)
                }
                Intent.ACTION_SCREEN_OFF -> {
                    dbHelper.logEvent("SCREEN_OFF", "Tela apagou")
                    AppUsageTracker.syncUsage(this@MonitorService, dbHelper)
                }
                Intent.ACTION_USER_PRESENT -> {
                    dbHelper.logEvent("USER_PRESENT", "Aparelho desbloqueado")
                    AppUsageTracker.syncUsage(this@MonitorService, dbHelper)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        dbHelper = EventDbHelper(this)
        createNotificationChannel()
        startForeground(1001, buildNotification())

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenReceiver, filter)
        dbHelper.logEvent("SERVICE_STARTED", "MonitorService iniciado")

        // Inicia sincronização de uso de apps
        AppUsageTracker.syncUsage(this, dbHelper)
        handler.postDelayed(periodicSyncRunnable, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(periodicSyncRunnable)
        AppUsageTracker.syncUsage(this, dbHelper)
        dbHelper.logEvent("SERVICE_STOPPED", "MonitorService destruido")
        unregisterReceiver(screenReceiver)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_monitor_channel",
                "Monitoramento de Tela e Apps",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, "screen_monitor_channel")
            .setContentTitle("PoC Monitor Ativo")
            .setContentText("Registrando eventos de tela e uso de apps...")
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}
