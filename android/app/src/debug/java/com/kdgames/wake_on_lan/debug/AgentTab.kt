package com.kdgames.wake_on_lan.debug

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.agent.AgentCommand
import com.kdgames.wake_on_lan.net.agent.AgentDisplay
import com.kdgames.wake_on_lan.net.agent.AgentResult
import com.kdgames.wake_on_lan.net.agent.AgentSession
import com.kdgames.wake_on_lan.net.agent.DiscoveredAgent
import com.kdgames.wake_on_lan.net.agent.NicSelector
import com.kdgames.wake_on_lan.net.agent.PairResult
import com.kdgames.wake_on_lan.net.probe.NetBiosProbe
import kotlinx.coroutines.launch
import java.io.IOException

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val Muted = Color(0xFFB0B4C0)

/** Debug'da tek bir eşleşme tutulur: hedef IP, agentId ve KeyVault takma adı (asıl kayıt S07'de Device'a). */
private class DebugPairing(context: Context, private val vault: KeyVault) {
    private val prefs = context.getSharedPreferences("debug_agent", Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(v) = prefs.edit { putString("host", v) }

    val agentId: String? get() = prefs.getString("agentId", null)

    fun save(agentId: String, key: ByteArray) {
        forget()
        val alias = vault.newAlias()
        vault.store(alias, key)
        prefs.edit { putString("agentId", agentId); putString("alias", alias) }
    }

    fun session(phoneId: String): AgentSession? {
        val id = agentId ?: return null
        val key = prefs.getString("alias", null)?.let(vault::load) ?: return null
        return AgentSession(id, phoneId, key)
    }

    fun forget() {
        prefs.getString("alias", null)?.let(vault::delete)
        prefs.edit { remove("agentId"); remove("alias") }
    }
}

private enum class DebugCommand(val label: String, val cmd: AgentCommand) {
    Lock("Kilitle", AgentCommand.LOCK),
    Sleep("Uyku", AgentCommand.SLEEP),
    Hibernate("Hazırda", AgentCommand.HIBERNATE),
    Restart("Yeniden", AgentCommand.RESTART),
    Shutdown("Kapat", AgentCommand.SHUTDOWN),
    ScreenOn("Ekranı aç (K22)", AgentCommand.SCREEN_ON),
}

/** S04: agent keşfi, TOFU eşleşme, imzalı durum, 6 komut ve NetBIOS (S2). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentTab(client: AgentClient, netBios: NetBiosProbe, monitor: NetworkMonitor, settings: SettingsStore, vault: KeyVault) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wifi by monitor.state.collectAsStateWithLifecycle()
    val pairing = remember { DebugPairing(context, vault) }

    var host by remember { mutableStateOf(pairing.host) }
    var pairedId by remember { mutableStateOf(pairing.agentId) }
    var found by remember { mutableStateOf<List<DiscoveredAgent>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var statusText by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var commandText by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var netBiosText by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var force by remember { mutableStateOf(true) }
    var patient by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<DebugCommand?>(null) }

    val hostValid = runCatching { BroadcastResolver.parse(host.trim()) }.isSuccess
    val phoneName = remember {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL
    }

    suspend fun session(): AgentSession? {
        val s = pairing.session(settings.ensurePhoneId())
        if (s == null) message = "Önce eşleş" to false
        return s
    }

    /** K6: PC'de reset yapılmışsa telefondaki anahtar silinir. */
    fun handleUnknownPhone() {
        pairing.forget()
        pairedId = null
        message = "PC bu telefonu tanımıyor (reset yapılmış). Telefondaki eşleşme silindi; tekrar \"Eşleş\"." to false
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.padding(top = 4.dp))
        Text("Keşif", style = MaterialTheme.typography.titleMedium)
        Button(enabled = !searching, onClick = {
            searching = true
            scope.launch {
                found = try {
                    client.discover(wifi.broadcast ?: throw NoWifiException())
                } catch (_: NoWifiException) {
                    message = "Wi-Fi yok" to false
                    null
                } catch (e: IOException) {
                    message = "Hata: ${e.message}" to false
                    null
                }
                searching = false
            }
        }) { Text(if (searching) "Aranıyor…" else "Ara") }
        found?.let { list ->
            if (list.isEmpty()) Text("Agent bulunamadı (PC'de servis çalışıyor mu, güvenlik duvarı?)", color = Fail)
            list.forEach { agent ->
                AgentCard(agent, wifi.phoneIp, wifi.prefixLength, selected = agent.host == host) {
                    host = agent.host
                    pairing.host = agent.host
                }
            }
        }

        HorizontalDivider()
        Text("Eşleşme", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = host, onValueChange = { host = it; pairing.host = it.trim() }, label = { Text("PC IP") },
            singleLine = true, isError = host.isNotBlank() && !hostValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(),
        )
        Text(
            pairedId?.let { "✓ Eşli · agent ${it.take(8)}…" } ?: "Eşleşme yok",
            color = if (pairedId != null) Pass else Muted,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = hostValid, onClick = {
                scope.launch {
                    message = "Eşleşiyor…" to true
                    message = try {
                        when (val r = client.pair(host.trim(), settings.ensurePhoneId(), phoneName)) {
                            is PairResult.Paired -> {
                                pairing.save(r.agentId, r.key)
                                pairedId = r.agentId
                                "Eşleşti ✓" to true
                            }
                            PairResult.Denied -> "Başka telefona eşli · PC'de `WolAgent.exe reset` çalıştır" to false
                            PairResult.ProofInvalid -> "Eşleşme doğrulanamadı (proof hatası)" to false
                            PairResult.NoResponse -> "Yanıt yok" to false
                            is PairResult.Failed -> "Hata: ${r.code}" to false
                        }
                    } catch (_: NoWifiException) {
                        "Wi-Fi yok" to false
                    }
                }
            }) { Text("Eşleş") }
            OutlinedButton(enabled = pairedId != null, onClick = {
                pairing.forget()
                pairedId = null
                message = "Telefondaki eşleşme silindi (PC tarafı değişmedi)" to true
            }) { Text("Telefonda unut") }
        }
        message?.let { (t, ok) -> Text(t, color = if (ok) Pass else Fail) }

        HorizontalDivider()
        Text("Durum (S1)", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Uzun bekle (uykudaki PC için, K22)", modifier = Modifier.weight(1f))
            Switch(checked = patient, onCheckedChange = { patient = it })
        }
        Button(enabled = hostValid && pairedId != null, onClick = {
            scope.launch {
                val s = session() ?: return@launch
                statusText = "Soruluyor…" to true
                statusText = try {
                    val r = if (patient) client.status(host.trim(), s, budgetMs = 12_000, attemptMs = 4_000)
                    else client.status(host.trim(), s)
                    when (r) {
                        is AgentResult.Ok -> {
                            val v = r.value
                            val display = when (v.display) {
                                AgentDisplay.ON -> "açık"
                                AgentDisplay.OFF -> "kapalı"
                                AgentDisplay.DIMMED -> "kısık"
                                else -> "bilinmiyor"
                            }
                            "${v.hostname} · çalışma ${formatUptime(v.uptimeSec)} · ekran $display · hazırda ${if (v.hibernate) "var" else "yok"} · imza ✓ · ${r.elapsedMs} ms" to true
                        }
                        AgentResult.UnknownPhone -> { handleUnknownPhone(); "UNKNOWN_PHONE" to false }
                        AgentResult.BadSignature -> "İmza doğrulanmadı ✗" to false
                        AgentResult.NoResponse -> "Yanıt yok" to false
                        is AgentResult.Error -> r.code to false
                    }
                } catch (_: NoWifiException) {
                    "Wi-Fi yok" to false
                }
            }
        }) { Text("Durum") }
        statusText?.let { (t, ok) -> Text(t, color = if (ok) Pass else Fail) }

        HorizontalDivider()
        Text("Komutlar", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Kapat/Yeniden: ${if (force) "Zorla" else "Nazik"}", modifier = Modifier.weight(1f))
            Switch(checked = force, onCheckedChange = { force = it })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DebugCommand.entries.forEach { c ->
                OutlinedButton(enabled = hostValid && pairedId != null, onClick = { confirm = c }) { Text(c.label) }
            }
        }
        commandText?.let { (t, ok) -> Text(t, color = if (ok) Pass else Fail) }

        HorizontalDivider()
        Text("NetBIOS (S2)", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(enabled = hostValid, onClick = {
            scope.launch {
                netBiosText = "Soruluyor…" to true
                netBiosText = try {
                    netBios.probe(host.trim())?.let { "${it.hostname ?: "ad yok"} · ${it.mac ?: "MAC yok"} · ${it.elapsedMs} ms" to true }
                        ?: ("Yanıt yok (PC'de NetBIOS kapalı olabilir)" to false)
                } catch (_: NoWifiException) {
                    "Wi-Fi yok" to false
                }
            }
        }) { Text("NetBIOS") }
        netBiosText?.let { (t, ok) -> Text(t, color = if (ok) Pass else Fail) }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    confirm?.let { c ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("${c.label} gönderilsin mi?") },
            text = {
                val mode = if (c.cmd == AgentCommand.SHUTDOWN || c.cmd == AgentCommand.RESTART) " (${if (force) "Zorla" else "Nazik"})" else ""
                Text("Hedef: $host$mode")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        val s = session() ?: return@launch
                        commandText = "${c.label}: gönderiliyor…" to true
                        commandText = try {
                            when (val r = client.command(host.trim(), s, c.cmd, force)) {
                                is AgentResult.Ok -> "${c.label}: ACK ✓ · ${r.elapsedMs} ms" to true
                                AgentResult.UnknownPhone -> { handleUnknownPhone(); "${c.label}: UNKNOWN_PHONE" to false }
                                AgentResult.BadSignature -> "${c.label}: ACK imzası doğrulanmadı ✗" to false
                                AgentResult.NoResponse -> "${c.label}: yanıt yok" to false
                                is AgentResult.Error -> "${c.label}: ${r.code}" to false
                            }
                        } catch (_: NoWifiException) {
                            "Wi-Fi yok" to false
                        }
                    }
                }) { Text("Gönder") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun AgentCard(agent: DiscoveredAgent, phoneIp: String?, prefix: Int?, selected: Boolean, onSelect: () -> Unit) {
    val r = agent.reply
    val chosen = NicSelector.select(r.nics, phoneIp, prefix)
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("${if (selected) "▶ " else ""}${r.hostname} · ${agent.host}", fontWeight = FontWeight.SemiBold)
            Mono("agent ${r.agentId.take(8)}… · v${r.agentVersion}")
            Text(
                "paired=${r.paired} · hazırda ${if (r.hibernate) "var" else "yok"}",
                color = if (r.paired) Muted else Pass, style = MaterialTheme.typography.bodySmall,
            )
            r.nics.forEach { nic ->
                val mark = if (nic == chosen) "★" else " "
                Mono("$mark ${if (nic.wired) "kablolu " else "kablosuz"} ${nic.mac} ${nic.ip.ifBlank { "IP yok" }} ${nic.name}")
            }
            Text("Seçmek için dokun · ★ = K16'ya göre WoL MAC'i", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Mono(text: String) =
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

private fun formatUptime(sec: Long): String {
    val d = sec / 86_400
    val h = sec % 86_400 / 3_600
    val m = sec % 3_600 / 60
    return if (d > 0) "${d}g ${h}s" else if (h > 0) "${h}s ${m}dk" else "${m}dk"
}
