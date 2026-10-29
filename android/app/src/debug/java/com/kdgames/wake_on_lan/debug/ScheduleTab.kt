package com.kdgames.wake_on_lan.debug

import android.content.Intent
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
private fun fmt(ms: Long?) = ms?.let { TIME.format(Date(it)) } ?: "—"

/**
 * S08: kurulu alarmlar (zamanlayıcı, sonraki tetik), "Şimdi tetikle" (alarmı beklemeden alıcıyı çağırır),
 * son `ScheduleRun` kayıtları + `attemptsJson`.
 */
@Composable
fun ScheduleTab(schedules: ScheduleRepository, devices: DeviceRepository, service: ScheduleService) {
    val context = LocalContext.current
    val list by schedules.observeSchedules().collectAsState(initial = emptyList())
    val devs by devices.observeDevices().collectAsState(initial = emptyList())
    val armed by service.armed.collectAsState()
    val runs by schedules.observeRecentRuns(20).collectAsState(initial = emptyList())
    val names = devs.associate { it.id to it.name }

    LazyColumn(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Tam saatli alarm: " + if (service.canScheduleExact()) "izinli" else "İZİN YOK",
                color = if (service.canScheduleExact()) Pass else Fail,
            )
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
