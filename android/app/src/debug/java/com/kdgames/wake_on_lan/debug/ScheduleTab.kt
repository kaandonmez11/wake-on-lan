package com.kdgames.wake_on_lan.debug

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.rememberCoroutineScope
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.schedule.AndroidScheduleNotifier
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.ScheduleRepository
import com.kdgames.wake_on_lan.domain.model.RunResult
import com.kdgames.wake_on_lan.domain.schedule.AndroidAlarmScheduler
import com.kdgames.wake_on_lan.domain.schedule.RunAttempts
import com.kdgames.wake_on_lan.domain.schedule.ScheduleAlarmReceiver
import com.kdgames.wake_on_lan.domain.schedule.ScheduleEngine
import com.kdgames.wake_on_lan.domain.schedule.ScheduleService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val Muted = Color(0xFFB0B4C0)
private val TIME = SimpleDateFormat("dd.MM HH:mm:ss", Locale.forLanguageTag("tr"))
private const val TEST_ID = 9_999L
private fun fmt(ms: Long?) = ms?.let { TIME.format(Date(it)) } ?: "—"

/**
 * S08: kurulu alarmlar (zamanlayıcı, sonraki tetik), "Şimdi tetikle" (alarmı beklemeden alıcıyı çağırır),
 * son `ScheduleRun` kayıtları + `attemptsJson`; "Test bildirimi" (gerçek bildirim yolu, ikon/metin denemesi).
 */
@Composable
fun ScheduleTab(schedules: ScheduleRepository, devices: DeviceRepository, service: ScheduleService) {
    val context = LocalContext.current
    val list by schedules.observeSchedules().collectAsState(initial = emptyList())
    val devs by devices.observeDevices().collectAsState(initial = emptyList())
    val armed by service.armed.collectAsState()
    val runs by schedules.observeRecentRuns(20).collectAsState(initial = emptyList())
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        withPermission {
                            scope.launch {
                                notifier.started(TEST_ID, "Test PC", PowerAction.WAKE)
                                delay(3_000)
                                notifier.finished(TEST_ID, "Test PC", PowerAction.WAKE, RunResult.SUCCESS, "Uyandı · 4 sn")
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
        item { Text("Zamanlayıcılar", style = MaterialTheme.typography.titleMedium) }
        items(list, key = { it.id }) { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("#${s.id} · ${names[s.deviceId] ?: "?"} · ${s.action} · ${ScheduleEngine.triggerLabel(s)}${if (!s.enabled) " · KAPALI" else ""}")
                    Text("Kurulu alarm: ${fmt(armed[s.id])}", fontFamily = FontFamily.Monospace)
                    Text("Atlanan: ${fmt(s.skippedOccurrence)}", fontFamily = FontFamily.Monospace, color = Muted)
                    Button(onClick = {
                        val intent = Intent(context, ScheduleAlarmReceiver::class.java)
                            .setAction(AndroidAlarmScheduler.ACTION_FIRE)
                            .putExtra(AndroidAlarmScheduler.EXTRA_ID, s.id)
                            .putExtra(AndroidAlarmScheduler.EXTRA_OCCURRENCE, System.currentTimeMillis())
                        context.sendBroadcast(intent)
                    }) { Text("Şimdi tetikle") }
                }
            }
        }
        item { Text("Son çalışmalar", style = MaterialTheme.typography.titleMedium) }
        items(runs, key = { it.id }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "#${r.scheduleId} · ${r.result}",
                        color = when (r.result) {
                            RunResult.SUCCESS -> Pass
                            RunResult.FAILED -> Fail
                            RunResult.RUNNING -> Color(0xFFFFD97A)
                            else -> Muted
                        },
                    )
                    Text("plan ${fmt(r.occurrence)} · başla ${fmt(r.startedAt)} · bitiş ${fmt(r.endedAt)}", fontFamily = FontFamily.Monospace)
                    RunAttempts.decode(r.attemptsJson).forEach { a ->
                        Text("  ${fmt(a.at)} ${a.outcome} · ${a.reason}", fontFamily = FontFamily.Monospace, color = Muted)
                    }
                    Text(r.attemptsJson, fontFamily = FontFamily.Monospace, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
