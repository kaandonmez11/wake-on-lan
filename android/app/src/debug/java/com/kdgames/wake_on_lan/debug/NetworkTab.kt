package com.kdgames.wake_on_lan.debug

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.MacAddress
import com.kdgames.wake_on_lan.net.NetPermissions
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.WolSender
import com.kdgames.wake_on_lan.net.probe.IcmpProbe
import com.kdgames.wake_on_lan.net.probe.TcpProbe
import kotlinx.coroutines.launch
import java.io.IOException

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val Muted = Color(0xFFB0B4C0)

/** S03: canlı Wi-Fi durumu, izinler, gerçek magic packet ve S3/S4 probe'ları. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkTab(monitor: NetworkMonitor, sender: WolSender, tcp: TcpProbe, icmp: IcmpProbe) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wifi by monitor.state.collectAsStateWithLifecycle()
    val prefs = remember { context.getSharedPreferences("debug_net", Context.MODE_PRIVATE) }

    // İzinler ayarlardan da değişebilir; her dönüşte yeniden okunur ve SSID için callback tazelenir.
    var permTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        permTick++
        monitor.refresh()
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        monitor.refresh()
    }

    var ip by remember { mutableStateOf(prefs.getString("ip", "") ?: "") }
    var mac by remember { mutableStateOf(prefs.getString("mac", "") ?: "") }
    val wakeLog = remember { mutableStateListOf<Pair<String, Boolean?>>() }
    var waking by remember { mutableStateOf(false) }
    var tcpResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pingResult by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    val ipValid = runCatching { BroadcastResolver.parse(ip.trim()) }.isSuccess
    val macValid = MacAddress.isValid(mac)
    fun saveTarget() = prefs.edit { putString("ip", ip.trim()); putString("mac", mac.trim()) }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.padding(top = 4.dp))
        WifiCard(wifi)

        HorizontalDivider()
        Text("İzinler", style = MaterialTheme.typography.titleMedium)
        val localNet = remember(permTick) { NetPermissions.hasLocalNetwork(context) }
        val location = remember(permTick) { NetPermissions.hasFineLocation(context) }
        val locationService = remember(permTick) { NetPermissions.isLocationServiceOn(context) }
        PermissionRow("Yerel ağ (ACCESS_LOCAL_NETWORK)", localNet) {
            launcher.launch(arrayOf(NetPermissions.LOCAL_NETWORK))
        }
        PermissionRow("Konum (SSID için)", location) {
            launcher.launch(NetPermissions.LOCATION)
        }
        StatusLine("Konum servisi", locationService, "açık", "kapalı (SSID gizlenir)")

        HorizontalDivider()
        Text("Hedef PC", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = ip, onValueChange = { ip = it }, label = { Text("IP") }, singleLine = true,
            isError = ip.isNotBlank() && !ipValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = mac, onValueChange = { mac = it }, label = { Text("MAC (AA:BB:CC:DD:EE:FF)") }, singleLine = true,
            isError = mac.isNotBlank() && !macValid, modifier = Modifier.fillMaxWidth(),
        )

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = macValid && !waking, onClick = {
                saveTarget()
                waking = true
                wakeLog.clear()
                scope.launch {
                    try {
                        wakeLog += "Broadcast: ${wifi.broadcast ?: "—"}" to null
                        sender.send(mac, wifi.broadcast) { round ->
                            val sec = "%.2f".format(round.atMs / 1000f)
                            val ok = round.results.count { it.second == null }
                            wakeLog += "t=$sec sn · $ok/${round.results.size} gönderildi" to round.allSent
                            round.results.filter { it.second != null }.forEach { (target, err) ->
                                wakeLog += "   $target → $err" to false
                            }
                        }
                        wakeLog += "Bitti: 3 tur" to true
                    } catch (_: NoWifiException) {
                        wakeLog += "Wi-Fi yok" to false
                    } catch (e: IOException) {
                        wakeLog += "Hata: ${e.message}" to false
                    } finally {
                        waking = false
                    }
                }
            }) { Text(if (waking) "Gönderiliyor…" else "Uyandır") }

            OutlinedButton(enabled = ipValid, onClick = {
                saveTarget()
                tcpResult = "Deneniyor…" to true
                scope.launch {
                    tcpResult = try {
                        val r = tcp.probe(ip.trim(), Device.DEFAULT_PROBE_PORTS)
                        if (r.alive) {
                            val how = if (r.accepted) "bağlandı" else "refused"
                            "Canlı · ${r.elapsedMs} ms (port ${r.port}, $how)" to true
                        } else "Yanıt yok · ${r.elapsedMs} ms" to false
                    } catch (_: NoWifiException) {
                        "Wi-Fi yok" to false
                    }
                }
            }) { Text("TCP probe") }

            OutlinedButton(enabled = ipValid, onClick = {
                saveTarget()
                pingResult = "Deneniyor…" to true
                scope.launch {
                    pingResult = when (val r = icmp.probe(ip.trim())) {
                        is IcmpProbe.Result.Reply -> "Yanıt · ${r.ms} ms" to true
                        is IcmpProbe.Result.NoReply ->
                            (if (r.blocked) "Engellendi (yerel ağ izni?) · ${r.elapsedMs} ms" else "Yanıt yok · ${r.elapsedMs} ms") to false
                        is IcmpProbe.Result.Unavailable -> "S4 kullanılamıyor (${r.reason})" to false
                    }
                }
            }) { Text("Ping") }
        }

        Text("TCP portları: ${Device.DEFAULT_PROBE_PORTS.joinToString()}", color = Muted, style = MaterialTheme.typography.bodySmall)
        tcpResult?.let { (text, ok) -> Text("TCP: $text", color = if (ok) Pass else Fail) }
        pingResult?.let { (text, ok) -> Text("Ping: $text", color = if (ok) Pass else Fail) }
        if (wakeLog.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Uyandırma günlüğü", style = MaterialTheme.typography.titleSmall)
                    wakeLog.forEach { (line, ok) ->
                        Text(
                            line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                            color = when (ok) { true -> Pass; false -> Fail; null -> Muted },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }
}

@Composable
private fun WifiCard(wifi: WifiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (wifi.connected) "● Bağlı · ${wifi.ssid ?: "Wi-Fi"}" else "○ Bağlı değil",
                color = if (wifi.connected) Pass else Fail,
                style = MaterialTheme.typography.titleMedium,
            )
            if (wifi.connected) {
                val bars = "▮".repeat(wifi.signalLevel) + "▯".repeat(4 - wifi.signalLevel)
                Mono("Sinyal    $bars ${wifi.signalLevel}/4 · ${wifi.rssi?.let { "$it dBm" } ?: "RSSI yok"}")
                Mono("Link      ${wifi.linkSpeedMbps?.let { "$it Mbps" } ?: "—"}")
                Mono("Telefon   ${wifi.phoneIp ?: "—"}${wifi.prefixLength?.let { "/$it" } ?: ""}")
                Mono("Broadcast ${wifi.broadcast ?: "—"}")
                Mono("Network   ${wifi.network}")
            }
        }
    }
}

@Composable
private fun Mono(text: String) =
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

@Composable
private fun PermissionRow(label: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${if (granted) "✓" else "✗"} $label", color = if (granted) Pass else Fail, modifier = Modifier.weight(1f))
        if (!granted) OutlinedButton(onClick = onRequest) { Text("İzin iste") }
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean, okText: String, badText: String) {
    Text("${if (ok) "✓" else "✗"} $label: ${if (ok) okText else badText}", color = if (ok) Pass else Fail)
}
