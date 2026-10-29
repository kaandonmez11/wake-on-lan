package com.kdgames.wake_on_lan.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.Settings
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.WakeHistory
import com.kdgames.wake_on_lan.domain.model.AttemptSource
import com.kdgames.wake_on_lan.domain.model.TileMode
import com.kdgames.wake_on_lan.ui.theme.DevicePalette
import kotlinx.coroutines.launch
import java.security.SecureRandom
import kotlin.random.Random

private val Pass = Color(0xFF7CFFB2)
private val Fail = Color(0xFFFF8F85)
private val SampleNames = listOf("Ofis PC", "Oyun PC", "NAS", "Salon TV", "Çalışma Laptop", "Yazıcı", "Mutfak Hoparlör")

/** S02: gerçek Room/DataStore/Keystore üzerinde cihaz, geçmiş, ayar ve anahtar denemeleri. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataTab(devices: DeviceRepository, settings: SettingsStore, vault: KeyVault, linker: com.kdgames.wake_on_lan.domain.agent.AgentLinker) {
    val scope = rememberCoroutineScope()
    val list by devices.observeDevices().collectAsState(initial = emptyList())
    val prefs by settings.settings.collectAsState(initial = Settings())
    var message by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var phoneId by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { phoneId = linker.phoneKeys.phoneId }

    fun show(text: String, ok: Boolean) { message = text to ok }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Spacer(Modifier.padding(top = 4.dp))
            Text("Cihazlar (${list.size})", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val input = sampleInput(list)
                        when (val r = devices.add(input)) {
                            is SaveResult.Saved -> show("Eklendi: ${input.name}", true)
                            is SaveResult.Invalid -> show(r.errors.joinToString { it.message }, false)
                        }
                    }
                }) { Text("Örnek cihaz ekle") }
                OutlinedButton(
                    enabled = list.any { it.mac != null },
                    onClick = {
                        scope.launch {
                            val mac = list.first { it.mac != null }.mac!!.lowercase().replace(':', '-')
                            when (val r = devices.add(DeviceInput("Kopya ${Random.nextInt(1000)}", "192.168.1.250", mac))) {
                                is SaveResult.Saved -> show("Beklenmedik: eklendi", false)
                                is SaveResult.Invalid -> show(r.errors.joinToString { it.message }, false)
                            }
                        }
                    },
                ) { Text("Aynı MAC ile ekle") }
            }
            message?.let { (text, ok) -> Text(text, color = if (ok) Pass else Fail) }
            RealDeviceForm(devices) { text, ok -> show(text, ok) }
        }

        items(list, key = { it.id }) { device -> DeviceRow(device, devices, linker) { text, ok -> show(text, ok) } }

        item {
            HorizontalDivider()
            KeystoreSection(vault)
            HorizontalDivider()
            SettingsSection(prefs, phoneId, settings)
            Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

@Composable
private fun DeviceRow(device: Device, repo: DeviceRepository, linker: com.kdgames.wake_on_lan.domain.agent.AgentLinker, onMessage: (String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    val history by repo.observeHistory(device.id).collectAsState(initial = WakeHistory(emptyList()))
    var n by remember { mutableStateOf(0L) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${device.sortOrder}", fontFamily = FontFamily.Monospace, color = DevicePalette.of(device.colorIndex).start)
                Spacer(Modifier.width(8.dp))
                Text(device.name, fontWeight = FontWeight.SemiBold)
            }
            Text("${device.ip} · ${device.mac ?: "MAC yok"}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            Text(
                text = if (history.isNew) "Geçmiş: yeni" else "Geçmiş: " + history.attempts.joinToString("") { if (it.success) "✓" else "✗" } + "  ·  ${history.label}",
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch { repo.recordWakeAttempt(device.id, System.currentTimeMillis() + n++, true, 5, AttemptSource.MANUAL) }
                }) { Text("+ ✓") }
                OutlinedButton(onClick = {
                    scope.launch { repo.recordWakeAttempt(device.id, System.currentTimeMillis() + n++, false, null, AttemptSource.MANUAL) }
                }) { Text("+ ✗") }
                OutlinedButton(onClick = {
                    scope.launch {
                        // Asıl paneldeki gibi: PC açıksa agent eşleşmesi de kalkar (K25).
                        repo.delete(device.id, keepAgentKey = true)
                        linker.release(device)
                        onMessage("Silindi: ${device.name}", true)
                    }
                }) { Text("Sil") }
            }
        }
    }
}

@Composable
private fun KeystoreSection(vault: KeyVault) {
    var result by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Keystore", style = MaterialTheme.typography.titleMedium)
        Button(onClick = {
            result = runCatching {
                val key = ByteArray(32).also(SecureRandom()::nextBytes)
                val alias = vault.newAlias()
                vault.store(alias, key)
                val loaded = vault.load(alias)
                vault.delete(alias)
                val ok = loaded != null && loaded.contentEquals(key) && vault.load(alias) == null
                (if (ok) "✓ Sarmala/aç eşit" else "✗ Açılan anahtar farklı") to ok
            }.getOrElse { "✗ ${it::class.simpleName}: ${it.message}" to false }
        }) { Text("Keystore testi") }
        result?.let { (text, ok) -> Text(text, color = if (ok) Pass else Fail) }
    }
}

@Composable
private fun SettingsSection(prefs: Settings, phoneId: String, store: SettingsStore) {
    val scope = rememberCoroutineScope()
    var interval by remember(prefs.retryIntervalMin) { mutableStateOf(prefs.retryIntervalMin.toString()) }
    var giveUp by remember(prefs.giveUpMin) { mutableStateOf(prefs.giveUpMin.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ayarlar (DataStore)", style = MaterialTheme.typography.titleMedium)
        Text("phoneId: $phoneId", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        Text("Tile slotları: ${prefs.tileSlots} · Seçili cihaz: ${prefs.selectedDeviceId}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = interval, onValueChange = { interval = it }, label = { Text("Tekrar aralığı (dk)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = giveUp, onValueChange = { giveUp = it }, label = { Text("Vazgeçme (dk)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Button(onClick = {
            scope.launch {
                val i = interval.toIntOrNull() ?: -1
                val g = giveUp.toIntOrNull() ?: -1
                val e = store.setRetryPolicy(i, g)
                error = e?.message
                if (e != null) {
                    // Geçersiz giriş: son geçerli değerlere dön.
                    interval = prefs.retryIntervalMin.toString()
                    giveUp = prefs.giveUpMin.toString()
                }
            }
        }) { Text("Kaydet") }
        Text(
            error ?: "Kayıtlı: ${prefs.retryIntervalMin} dk / ${prefs.giveUpMin} dk",
            color = if (error != null) Fail else Pass,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Titreşim", modifier = Modifier.weight(1f))
            Switch(checked = prefs.hapticsEnabled, onCheckedChange = { scope.launch { store.setHapticsEnabled(it) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tile modu")
            TileMode.entries.forEach { mode ->
                FilterChip(selected = prefs.tileMode == mode, onClick = { scope.launch { store.setTileMode(mode) } }, label = { Text(mode.name) })
            }
        }
    }
}

private fun sampleInput(existing: List<Device>): DeviceInput {
    val used = existing.map { it.name }.toSet()
    val base = SampleNames.firstOrNull { it !in used } ?: "Cihaz ${Random.nextInt(100, 999)}"
    val mac = List(6) { "%02X".format(Random.nextInt(256)) }.joinToString(":")
    return DeviceInput(
        name = base,
        ip = "192.168.1.${Random.nextInt(10, 240)}",
        mac = mac,
        iconKey = "pc",
        colorIndex = Random.nextInt(DevicePalette.entries.size),
    )
}

/** S05: gerçek PC'yi ad + IP + (isteğe bağlı) MAC ile ekle. MAC boş bırakılırsa PC açıkken otomatik dolar. */
@Composable
private fun RealDeviceForm(devices: DeviceRepository, onMessage: (String, Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Gerçek cihaz ekle", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Ad") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = ip, onValueChange = { ip = it }, label = { Text("IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                OutlinedTextField(value = mac, onValueChange = { mac = it }, label = { Text("MAC (boş olabilir)") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                scope.launch {
                    when (val r = devices.add(DeviceInput(name, ip, mac.ifBlank { null }, iconKey = "pc", colorIndex = 0))) {
                        is SaveResult.Saved -> {
                            onMessage("Eklendi: ${name.trim()}", true)
                            name = ""; ip = ""; mac = ""
                        }
                        is SaveResult.Invalid -> onMessage(r.errors.joinToString { it.message }, false)
                    }
                }
            }) { Text("Ekle") }
        }
    }
}
