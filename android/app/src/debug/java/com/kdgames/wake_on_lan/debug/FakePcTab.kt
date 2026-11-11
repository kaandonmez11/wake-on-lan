package com.kdgames.wake_on_lan.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * S09 (Kaan): "Sahte PC". Gerçek PC'yi açıp kapatmadan zamanlayıcı, retry ve bildirim testleri. Uygulamada
 * normal bir cihaz gibi görünür; sinyal turu, magic packet ve agent komutu bu ayarlara göre cevaplanır.
 */
@Composable
fun FakePcTab(fake: DebugFakePc, devices: DeviceRepository) {
    val scope = rememberCoroutineScope()
    val state by fake.state.collectAsState()
    val list by devices.observeDevices().collectAsState(initial = emptyList())
    val device = list.firstOrNull { fake.handles(it) }
    // Eski sahte PC kaydında anahtar takma adı yoktu; güç komutları "Agent bulunamadı" diye reddediliyordu (S12).
    LaunchedEffect(device?.id, device?.agentKeyAlias) {
        if (device != null && device.agentKeyAlias == null) devices.updateDevice(device.copy(agentId = DebugFakePc.AGENT_ID, agentKeyAlias = DebugFakePc.KEY_ALIAS))
    }
    // Geçişler (açılıyor/kapanıyor) zamanla uygulanır: ekran açıkken saniyede bir yenile.
    LaunchedEffect(Unit) {
        while (true) {
            fake.current()
            delay(1_000)
        }
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cihaz", style = MaterialTheme.typography.titleMedium)
                if (device == null) {
                    Text("Sahte PC uygulamada yok. Ekleyince Cihazlar'da \"${DebugFakePc.NAME}\" olarak görünür (agent eşli sayılır).")
                    Button(onClick = {
                        scope.launch {
                            val r = devices.add(DeviceInput(DebugFakePc.NAME, DebugFakePc.IP, DebugFakePc.MAC, iconKey = "pc", colorIndex = 3))
                            if (r is SaveResult.Saved) devices.getDevice(r.id)?.let {
                                devices.updateDevice(it.copy(agentId = DebugFakePc.AGENT_ID, agentKeyAlias = DebugFakePc.KEY_ALIAS, hibernateAvailable = true))
                            }
                        }
                    }) { Text("Sahte PC ekle") }
                } else {
                    Text("Ekli: #${device.id} · ${device.ip} · ${device.mac}")
                    OutlinedButton(onClick = { scope.launch { devices.delete(device.id) } }) { Text("Sahte PC'yi sil") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Güç", style = MaterialTheme.typography.titleMedium)
                val (text, color) = when {
                    state.on && state.offAt != null -> "Kapanıyor…" to Color(0xFFFFD97A)
                    state.on -> "Açık · ${((System.currentTimeMillis() - state.bootedAt) / 1000).coerceAtLeast(0)} sn" to Color(0xFF7CFFB2)
                    state.onAt != null -> "Açılıyor…" to Color(0xFFFFD97A)
                    else -> "Kapalı" to Color(0xFFFF8F85)
                }
                Text(text, color = color, style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { fake.powerNow(true) }) { Text("Şimdi aç") }
                    Button(onClick = { fake.powerNow(false) }) { Text("Şimdi kapat") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Magic packet gelince", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FakeWake.entries.forEach { w ->
                        FilterChip(selected = state.wake == w, onClick = { fake.update { it.copy(wake = w) } }, label = { Text(w.label) })
                    }
                }
                if (state.wake == FakeWake.AFTER_N) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { fake.update { it.copy(wakeAfter = (it.wakeAfter - 1).coerceAtLeast(1)) } }) { Text("−") }
                        Text("${state.wakeAfter}. paket turunda açılır")
                        OutlinedButton(onClick = { fake.update { it.copy(wakeAfter = (it.wakeAfter + 1).coerceAtMost(12)) } }) { Text("+") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gelen paket turu: ${state.packets}")
                    OutlinedButton(onClick = { fake.update { it.copy(packets = 0) } }) { Text("Sıfırla") }
                }
                Text("Paketle açılma 5 sn sürer. Her deneme bir paket turudur.", color = Color(0xFFB0B4C0))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent komutları", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FakeAgent.entries.forEach { a ->
                        FilterChip(selected = state.agent == a, onClick = { fake.update { it.copy(agent = a) } }, label = { Text(a.label) })
                    }
                }
                Text("Uygular: Kapat/Uyku/Hazırda 5 sn'de kapanır, Yeniden 15 sn'de geri gelir.", color = Color(0xFFB0B4C0))
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Not", style = MaterialTheme.typography.titleMedium)
                Text("• Telefonun Wi-Fi'ı gerçek: Wi-Fi kapalıyken sahte PC'ye de ulaşılmaz (\"Wi-Fi yok\" denemesi).")
                Text("• Durum telefonda saklanır: alarm uygulamayı arka planda açsa da aynı sahte PC görülür.")
                Text("• Gerçek PC'ye dokunmaz; yalnız bu MAC'li cihaz sahtedir.")
            }
        }
    }
}
