package com.example.screenusagepoc

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.screenusagepoc.ui.theme.ScreenUsagePoCTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var dbHelper: EventDbHelper
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val todayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Permissão tratada */ }

    // Estados para Compose
    private var isServiceRunningState by mutableStateOf(false)
    private var screenEventCountState by mutableIntStateOf(0)
    private var appSessionCountState by mutableIntStateOf(0)
    private var hasUsagePermissionState by mutableStateOf(false)
    private var todaySummariesState by mutableStateOf<List<AppUsageSummary>>(emptyList())
    private var recentSessionsState by mutableStateOf<List<AppSession>>(emptyList())
    private var recentScreenEventsState by mutableStateOf<List<ScreenEvent>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dbHelper = EventDbHelper(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        refreshAll()

        setContent {
            ScreenUsagePoCTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PocScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppUsageTracker.syncUsage(this, dbHelper)
        refreshAll()
    }

    private fun refreshAll() {
        isServiceRunningState = MonitorService.isRunning
        hasUsagePermissionState = AppUsageTracker.hasUsageStatsPermission(this)
        screenEventCountState = dbHelper.getEventCount()
        appSessionCountState = dbHelper.getAppSessionCount()

        val today = todayDateFormat.format(Date())
        todaySummariesState = dbHelper.getTodayAppUsageSummaries(today)
        recentSessionsState = dbHelper.getRecentAppSessions(50)
        recentScreenEventsState = dbHelper.getRecentScreenEvents(30)
    }

    @Composable
    private fun PocScreen() {
        var selectedTabIndex by remember { mutableIntStateOf(0) }
        val tabTitles = listOf("Resumo Apps (Hoje)", "Linha do Tempo", "Eventos Tela")

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Cabeçalho
            Text(
                text = "PoC - Monitor de Uso & Apps",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            // Card com resumo e status
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Serviço de Fundo: ${if (isServiceRunningState) "🟢 ATIVO" else "⚪ PARADO"}",
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Permissão Uso: ${if (hasUsagePermissionState) "✅ OK" else "⚠️ PENDENTE"}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Sessões de Apps: $appSessionCountState", style = MaterialTheme.typography.bodyMedium)
                        Text("Eventos de Tela: $screenEventCountState", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            // Botões de ação rápida
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val serviceIntent = Intent(this@MainActivity, MonitorService::class.java)
                        if (isServiceRunningState) {
                            stopService(serviceIntent)
                            Toast.makeText(this@MainActivity, "Serviço parado", Toast.LENGTH_SHORT).show()
                        } else {
                            ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                            Toast.makeText(this@MainActivity, "Serviço iniciado!", Toast.LENGTH_SHORT).show()
                        }
                        refreshAll()
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isServiceRunningState) "Parar Serviço" else "Iniciar Serviço")
                }

                if (!hasUsagePermissionState) {
                    OutlinedButton(
                        onClick = {
                            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Liberar Permissão")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            AppUsageTracker.syncUsage(this@MainActivity, dbHelper)
                            refreshAll()
                            Toast.makeText(this@MainActivity, "Sincronizado!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Sincronizar")
                    }
                }
            }

            // Botão Exportar CSVs
            FilledTonalButton(
                onClick = { exportReports() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Exportar Relatórios CSV (Tela e Apps)")
            }

            // Abas de visualização
            TabRow(selectedTabIndex = selectedTabIndex) {
                tabTitles.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            // Conteúdo da Aba Selecionada
            Box(modifier = Modifier.weight(1f)) {
                when (selectedTabIndex) {
                    0 -> AppSummaryList(todaySummariesState)
                    1 -> AppSessionTimeline(recentSessionsState)
                    2 -> ScreenEventsList(recentScreenEventsState)
                }
            }
        }
    }

    @Composable
    private fun AppSummaryList(summaries: List<AppUsageSummary>) {
        if (summaries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma sessão de app registrada hoje ainda.\nUse o celular ou toque em 'Sincronizar'.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(summaries) { item ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.appName,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = "${item.sessionCount} sessões • ${item.packageName}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = AppUsageTracker.formatDuration(item.totalDurationMs),
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AppSessionTimeline(sessions: List<AppSession>) {
        if (sessions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhuma sessão detalhada encontrada.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(sessions) { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(session.appName, fontWeight = FontWeight.Bold)
                                Text(
                                    AppUsageTracker.formatDuration(session.durationMs),
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "Abriu: ${timeFormat.format(Date(session.startTime))}  -  Fechou: ${timeFormat.format(Date(session.endTime))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    session.date,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ScreenEventsList(events: List<ScreenEvent>) {
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Nenhum evento de tela registrado ainda.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(events) { ev ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(ev.eventType, fontWeight = FontWeight.Bold)
                                Text(ev.details, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                timeFormat.format(Date(ev.timestamp)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    }

    private fun exportReports() {
        try {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val screenFile = dbHelper.exportScreenEventsCsv(downloadsDir)
            val appsFile = dbHelper.exportAppSessionsCsv(downloadsDir)
            Toast.makeText(
                this,
                "Arquivos salvos em Downloads:\n${screenFile.name}\n${appsFile.name}",
                Toast.LENGTH_LONG
            ).show()
        } catch (_: Exception) {
            try {
                val fallbackDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
                val screenFile = dbHelper.exportScreenEventsCsv(fallbackDir)
                val appsFile = dbHelper.exportAppSessionsCsv(fallbackDir)
                Toast.makeText(
                    this,
                    "Salvo em: ${fallbackDir.name}:\n${screenFile.name}\n${appsFile.name}",
                    Toast.LENGTH_LONG
                ).show()
            } catch (ex: Exception) {
                Toast.makeText(this, "Erro ao exportar: ${ex.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
