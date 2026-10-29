package com.kdgames.wake_on_lan.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.domain.DomainEvents
import com.kdgames.wake_on_lan.domain.agent.AgentIssue
import com.kdgames.wake_on_lan.domain.agent.AgentLinker
import com.kdgames.wake_on_lan.domain.model.DeviceState
import com.kdgames.wake_on_lan.domain.model.PowerAction
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.domain.model.ShutdownMode
import com.kdgames.wake_on_lan.domain.model.Verdict
import com.kdgames.wake_on_lan.domain.power.PowerCoordinator
import com.kdgames.wake_on_lan.domain.status.StateLabels
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import com.kdgames.wake_on_lan.domain.wake.WakeCoordinator
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val Amber = Color(0xFFFFC86B)
private val Muted = Color(0xFFB0B4C0)

private val PowerButtons = listOf(
    PowerAction.LOCK to "Kilitle",
    PowerAction.SLEEP to "Uyku",
    PowerAction.HIBERNATE to "Hazırda",
    PowerAction.RESTART to "Yeniden",
    PowerAction.SHUTDOWN to "Kapat",
)

/** S05: StatusEngine'in canlı kararı, son turun sinyalleri, uyandırma/güç akışları ve olay günlüğü. */
@Composable
fun StatusTab(
    repo: DeviceRepository,
    engine: StatusEngine,
    wake: WakeCoordinator,
    power: PowerCoordinator,
    linker: AgentLinker,
    events: DomainEvents,
) {
    val devices by repo.observeDevices().collectAsState(initial = emptyList())
    val states by engine.states.collectAsState()
    val rounds by engine.rounds.collectAsState()
    val issues by linker.issues.collectAsState()
    val currentDevices by rememberUpdatedState(devices)
    val scope = rememberCoroutineScope()
    val log = remember { mutableStateListOf<String>() }
    var confirm by remember { mutableStateOf<Triple<Device, PowerAction, String>?>(null) }
    val time = remember { SimpleDateFormat("HH:mm:ss", Locale.ROOT) }

    fun addLog(line: String) {
        log.add(0, "${time.format(Date())}  $line")
        if (log.size > 80) log.removeAt(log.lastIndex)
    }

    LaunchedEffect(Unit) { events.events.collect { e -> addLog("${currentDevices.nameOf(e.deviceId)}: ${e.text}") } }
    // Durum geçişleri (aynı türde kalan ms değişimleri yazılmaz).
    LaunchedEffect(Unit) {
        var last = emptyMap<Long, String>()
        engine.states.collect { map ->
            map.forEach { (id, s) ->
                val label = StateLabels.of(s).substringBefore(" ·").let { if (s is DeviceState.Waking) "Uyanıyor · ${StateLabels.stepLabel(s.step)}" else it }
                if (last[id] != label) addLog("${currentDevices.nameOf(id)} → $label")
            }
            last = map.mapValues { (_, s) ->
                StateLabels.of(s).substringBefore(" ·").let { if (s is DeviceState.Waking) "Uyanıyor · ${StateLabels.stepLabel(s.step)}" else it }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            Text("Cihazlar Veri sekmesinden eklenir. Turlar uygulama ön plandayken 3 sn'de bir.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (devices.isEmpty()) item { Text("Henüz cihaz yok", color = Fail) }
        items(devices, key = { it.id }) { device ->
            DeviceStatusCard(
                device = device,
                state = states[device.id],
                round = rounds[device.id],
                issue = issues[device.id],
                canWake = device.mac != null && states[device.id] !is DeviceState.Waking && states[device.id] !is DeviceState.PowerTransition,
                powerEnabled = { action -> power.rejection(device.id, action) == null },
                onWake = { wake.wake(device.id) },
                onToggleMode = {
                    val next = if (device.shutdownMode == ShutdownMode.FORCE) ShutdownMode.GRACEFUL else ShutdownMode.FORCE
                    scope.launch { repo.updateDevice(device.copy(shutdownMode = next)) }
                },
                onPower = { action, label -> confirm = Triple(device, action, label) },
            )
        }
        item {
            HorizontalDivider()
            Text("Olay günlüğü", style = MaterialTheme.typography.titleMedium)
        }
        items(log) { line -> Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }

    confirm?.let { (device, action, label) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("$label gönderilsin mi?") },
            text = { Text("${device.name} · ${device.ip}" + if (action == PowerAction.SHUTDOWN || action == PowerAction.RESTART) " · ${device.shutdownMode.label()}" else "") },
            confirmButton = { TextButton(onClick = { confirm = null; power.execute(device.id, action) }) { Text("Gönder") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceStatusCard(
    device: Device,
    state: DeviceState?,
    round: StatusEngine.RoundInfo?,
    issue: AgentIssue?,
    canWake: Boolean,
    powerEnabled: (PowerAction) -> Boolean,
    onWake: () -> Unit,
    onToggleMode: () -> Unit,
    onPower: (PowerAction, String) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Mono("${device.ip} · ${device.mac ?: "MAC bekleniyor"}${device.hostname?.let { " · $it" } ?: ""}")
            Text(
                StateLabels.of(state),
                style = MaterialTheme.typography.titleMedium,
                color = when (state) {
                    is DeviceState.On -> if (state.stale) Muted else Pass
                    is DeviceState.Waking, is DeviceState.PowerTransition -> Amber
                    else -> if (state?.stale == true) Muted else Fail
                },
            )
            Mono(flags(device, state, issue))
            round?.let {
                Mono(signalLine(device, it.signals))
                val verdict = when (val v = it.verdict) {
                    is Verdict.Positive -> "pozitif"
                    is Verdict.Negative -> if (v.macMismatch) "negatif (farklı MAC, K8)" else "negatif"
                }
                Mono("tur #${it.number} · $verdict · ardışık negatif ${it.negatives}")
            } ?: Mono("henüz tur yok")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(enabled = canWake, onClick = onWake) { Text("Uyandır") }
                PowerButtons.forEach { (action, label) ->
                    OutlinedButton(enabled = powerEnabled(action), onClick = { onPower(action, label) }) { Text(label) }
                }
                TextButton(onClick = onToggleMode) { Text("Kapat/Yeniden: ${device.shutdownMode.label()}") }
            }
            if (device.mac == null) Text("MAC bekleniyor · PC açıkken otomatik bulunur", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (device.sleepHibernates) Text("Bu PC'de Uyku = Hazırda Beklet (K23)", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun flags(device: Device, state: DeviceState?, issue: AgentIssue?): String {
    val agent = when {
        issue == AgentIssue.PAIRED_ELSEWHERE -> "agent: başka telefona eşli (WolAgent.exe reset)"
        device.agentId == null -> "agent: yok"
        state is DeviceState.On && !state.agentReachable -> "agent: CEVAP VERMİYOR"
        else -> "agent: ✓"
    }
    val on = state as? DeviceState.On
    return listOfNotNull(
        on?.let { "verified=${it.verified}" },
        "stale=${state?.stale ?: false}",
        agent,
        on?.uptimeSec?.let { "uptime ${it / 60} dk" },
    ).joinToString(" · ")
}

private fun signalLine(device: Device, s: RoundSignals): String {
    val s1 = when {
        s.s1 == null -> "S1 —"
        s.s1.ok -> "S1 ✓ ${s.s1.ms}ms"
        s.s1.error != null -> "S1 ✗ ${s.s1.error}"
        else -> "S1 ✗"
    }
    val s2 = when {
        s.s2 == null -> "S2 ✗"
        s.s2.mac == null -> "S2 ✓ ${s.s2.ms}ms"
        device.mac == null -> "S2 ✓ (MAC ${s.s2.mac})"
        s.s2.mac.equals(device.mac, ignoreCase = true) -> "S2 ✓ MAC eş"
        else -> "S2 ⚠ farklı MAC"
    }
    val s3 = s.s3?.let { if (it.alive) "S3 ✓ ${it.ms}ms" else "S3 ✗" } ?: "S3 —"
    val s4 = s.s4?.let { if (it.unavailable) "S4 —" else if (it.reply) "S4 ✓ ${it.ms}ms" else "S4 ✗" } ?: "S4 —"
    return "$s1 · $s2 · $s3 · $s4"
}

private fun ShutdownMode.label() = if (this == ShutdownMode.FORCE) "Zorla" else "Nazik"

private fun List<Device>.nameOf(id: Long) = firstOrNull { it.id == id }?.name ?: "#$id"

@Composable
private fun Mono(text: String) =
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
