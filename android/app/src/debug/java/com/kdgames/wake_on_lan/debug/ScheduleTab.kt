package com.kdgames.wake_on_lan.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.db.ScheduleRun
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.schedule.AndroidAlarmScheduler
import com.kdgames.wake_on_lan.domain.schedule.AndroidRetryAlarms
import com.kdgames.wake_on_lan.domain.schedule.AndroidScheduleNotifier
import com.kdgames.wake_on_lan.domain.schedule.AttemptOutcome
import com.kdgames.wake_on_lan.domain.schedule.RunAttempt
import com.kdgames.wake_on_lan.domain.schedule.RunAttempts
import com.kdgames.wake_on_lan.domain.schedule.ScheduleAlarmReceiver
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.ScheduleService
import com.kdgames.wake_on_lan.domain.schedule.WifiTriggerLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val Warn = Color(0xFFFFD97A)
private val Muted = Color(0xFFB0B4C0)
private val TIME = SimpleDateFormat("dd.MM HH:mm:ss", Locale.forLanguageTag("tr"))
private const val TEST_ID = 9_999L
private fun fmt(ms: Long?) = ms?.let { TIME.format(Date(it)) } ?: "—"

/**
 * S08: kurulu alarmlar, "Şimdi tetikle", son `ScheduleRun` kayıtları + `attemptsJson`, "Test bildirimi".
 * S09: süren retry zincirleri (sonraki deneme), "Sahte FAILED üret" (bildirim + başarısız ekranı), Wi-Fi tetik günlüğü.
 */
@Composable
fun ScheduleTab(
    schedules: ScheduleRepository,
    devices: DeviceRepository,
    service: ScheduleService,
    retryAlarms: AndroidRetryAlarms,
    wifiLog: WifiTriggerLog,
) {
    val context = LocalContext.current
    val list by schedules.observeSchedules().collectAsState(initial = emptyList())
    val devs by devices.observeDevices().collectAsState(initial = emptyList())
    val armed by service.armed.collectAsState()
    val chains by retryAlarms.armed.collectAsState()
    val runs by schedules.observeRecentRuns(20).collectAsState(initial = emptyList())
    val log by wifiLog.lines.collectAsState()
    val names = devs.associate { it.id to it.name }
    val scope = rememberCoroutineScope()
    val notifier = remember { AndroidScheduleNotifier(context.applicationContext) }
    // İzin yoksa önce iste, verilince bildirimi gönder.
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke()
        pending = null
    }
    fun withPermission(block: () -> Unit) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) block()
        else {
            pending = block
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Tam saatli alarm: " + if (service.canScheduleExact()) "izinli" else "İZİN YOK",
                color = if (service.canScheduleExact()) Pass else Fail,
            )
        }
        item {
            // Gerçek bildirim yolu (AndroidScheduleNotifier): emir, 3 sn sonra sonuç; aynı bildirimin yerine geçer.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Test bildirimi", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        withPermission {
                            scope.launch {
                                notifier.started(TEST_ID, "Test PC", PowerAction.WAKE)
                                delay(3_000)
                                notifier.finished(TEST_ID, "Test PC", PowerAction.WAKE, RunResult.SUCCESS, "4 sn'de")
                            }
                        }
                    }) { Text("Başarılı") }
                    Button(onClick = {
                        withPermission {
                            scope.launch {
                                notifier.started(TEST_ID, "Test PC", PowerAction.WAKE)
                                delay(3_000)
                                notifier.finished(TEST_ID, "Test PC", PowerAction.WAKE, RunResult.FAILED, "Paket gitti · cevap yok")
                            }
                        }
                    }) { Text("Başarısız") }
                }
            }
        }
        item { Text("Süren retry zincirleri", style = MaterialTheme.typography.titleMedium) }
        val running = runs.filter { it.result == RunResult.RUNNING }
        if (running.isEmpty()) item { Text("Yok", color = Muted) }
        items(running, key = { "chain-${it.id}" }) { r ->
            val attempts = RunAttempts.decode(r.attemptsJson)
            val next = chains[r.id]
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Çalışma #${r.id} · zamanlayıcı #${r.scheduleId} · ${attempts.size} deneme yapıldı", color = Warn)
                    Text(
                        if (next != null) "Sonraki: ${next.first + 1}. deneme ${fmt(next.second)}" else "Sonraki deneme: (bu süreçte kurulmadı ya da deneme sürüyor)",
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
        item { Text("Zamanlayıcılar", style = MaterialTheme.typography.titleMedium) }
        items(list, key = { "schedule-${it.id}" }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("#${s.id} · ${names[s.deviceId] ?: "?"} · ${s.action} · ${ScheduleEngine.triggerLabel(s)}${if (!s.enabled) " · KAPALI" else ""}")
                    Text("Kurulu alarm: ${fmt(armed[s.id])}", fontFamily = FontFamily.Monospace)
                    Text("Atlanan: ${fmt(s.skippedOccurrence)}${if (s.skipNextEvent) " · sonraki olay atlanacak" else ""}", fontFamily = FontFamily.Monospace, color = Muted)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            val intent = Intent(context, ScheduleAlarmReceiver::class.java)
                                .setAction(AndroidAlarmScheduler.ACTION_FIRE)
                                .putExtra(AndroidAlarmScheduler.EXTRA_ID, s.id)
                                .putExtra(AndroidAlarmScheduler.EXTRA_OCCURRENCE, System.currentTimeMillis())
                            context.sendBroadcast(intent)
                        }) { Text("Şimdi tetikle") }
                        OutlinedButton(onClick = {
                            withPermission {
                                scope.launch {
                                    // 2 dk arayla 6 başarısız deneme; bildirime dokununca başarısız ekranı açılır.
                                    val end = System.currentTimeMillis()
                                    val start = end - 10 * 60_000L
                                    val reason = if (s.action == PowerAction.WAKE) "Paket gitti · cevap yok" else "Agent cevap vermedi"
                                    val outcome = if (s.action == PowerAction.WAKE) AttemptOutcome.NO_REPLY else AttemptOutcome.AGENT_NO_REPLY
                                    val attempts = (0 until 6).map { RunAttempt(start + it * 120_000L, if (it == 2) AttemptOutcome.NO_WIFI else outcome, if (it == 2) "Wi-Fi yok" else reason) }
                                    val id = schedules.insertRun(
                                        ScheduleRun(scheduleId = s.id, occurrence = start, startedAt = start, endedAt = end, result = RunResult.FAILED, attemptsJson = RunAttempts.encode(attempts)),
                                    )
                                    notifier.failed(s.id, id, names[s.deviceId] ?: "Cihaz", s.action, attempts)
                                    // Beklemeden başarısız ekranı (S10, Kaan): bildirime dokunmuş gibi uygulamayı açar.
                                    context.startActivity(
                                        com.kdgames.wake_on_lan.MainActivity.failureIntent(context, id)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                                    )
                                }
                            }
                        }) { Text("Başarısız yap · ekranı aç") }
                    }
                }
            }
        }
        item { Text("Eve gelince günlüğü", style = MaterialTheme.typography.titleMedium) }
        item {
            val known = wifiLog.known()
            Text(
                if (known.isEmpty()) "Öğrenilen ağ imzası yok (uygulama ön plandayken SSID okununca öğrenilir)"
                else "Öğrenilen ağlar: " + known.entries.joinToString { "${it.value} [${it.key}]" },
                color = Muted,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            val armed = wifiLog.armed
            Text(
                when {
                    !wifiLog.wanted -> "Sistem kaydı: yok (açık Eve gelince zamanlayıcısı yok)"
                    armed -> "Sistem kaydı: KURULU · bir sonraki Wi-Fi bağlantısı yakalanır"
                    else -> "Sistem kaydı: bekliyor · Wi-Fi'dan çıkınca kurulur (uygulama açıksa hemen, değilse ≤15 dk)"
                },
                color = if (armed || !wifiLog.wanted) Pass else Warn,
            )
        }
        if (log.isEmpty()) item { Text("Henüz Wi-Fi tetiği yok", color = Muted) }
        itemsIndexed(log, key = { i, _ -> "log-$i" }) { _, line ->
            Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = if ("tetiklendi" in line) Pass else Muted)
        }
        item { Text("Son çalışmalar", style = MaterialTheme.typography.titleMedium) }
        items(runs, key = { "run-${it.id}" }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "#${r.id} · zamanlayıcı #${r.scheduleId} · ${r.result}",
                        color = when (r.result) {
                            RunResult.SUCCESS -> Pass
                            RunResult.FAILED -> Fail
                            RunResult.RUNNING -> Warn
                            else -> Muted
                        },
                    )
                    Text("plan ${fmt(r.occurrence)} · başla ${fmt(r.startedAt)} · bitiş ${fmt(r.endedAt)}", fontFamily = FontFamily.Monospace)
                    RunAttempts.decode(r.attemptsJson).forEach { a ->
                        Text("  ${fmt(a.at)} ${a.outcome} · ${a.reason}", fontFamily = FontFamily.Monospace, color = Muted)
                    }
                }
            }
        }
    }
}
