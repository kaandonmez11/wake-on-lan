package com.kdgames.wake_on_lan.ui.sheet

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.repo.DeviceError
import com.kdgames.wake_on_lan.data.repo.DeviceInput
import com.kdgames.wake_on_lan.data.repo.DeviceRepository
import com.kdgames.wake_on_lan.data.repo.DeviceWarning
import com.kdgames.wake_on_lan.data.repo.SaveResult
import com.kdgames.wake_on_lan.data.repo.Validation
import com.kdgames.wake_on_lan.domain.agent.AgentIssue
import com.kdgames.wake_on_lan.domain.agent.AgentLinkTrigger
import com.kdgames.wake_on_lan.net.WifiStateSource
import com.kdgames.wake_on_lan.net.scan.AgentFound
import com.kdgames.wake_on_lan.net.scan.DeviceScanner
import com.kdgames.wake_on_lan.net.scan.ScanEntry
import com.kdgames.wake_on_lan.net.scan.ScanSnapshot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Panel açılış isteği: ekleme ya da bir cihazı düzenleme. */
sealed interface SheetMode {
    data object Add : SheetMode
    data class Edit(val id: Long) : SheetMode
}

data class SheetForm(
    val name: String = "",
    val ip: String = "",
    val mac: String = "",
    val iconKey: String = "pc",
    val colorIndex: Int = 0,
)

/** Düzenlenen cihazın kayıtlı agent durumu (önizleme ve ipucu için). */
enum class SavedAgent { NONE, PAIRED, PAIRED_ELSEWHERE }

data class SheetUiState(
    val mode: SheetMode = SheetMode.Add,
    val form: SheetForm = SheetForm(),
    val scan: ScanSnapshot = ScanSnapshot(),
    /** Taramadan seçilen satır (vurgu). */
    val pickedKey: String? = null,
    val errors: Set<DeviceError> = setOf(DeviceError.NAME_INVALID, DeviceError.IP_INVALID),
    val warnings: Set<DeviceWarning> = emptySet(),
    val savedAgent: SavedAgent = SavedAgent.NONE,
    val saving: Boolean = false,
) {
    val isEdit: Boolean get() = mode is SheetMode.Edit
    val canSave: Boolean get() = errors.isEmpty() && !saving

    /** Formdaki IP/MAC taramada agent'lı bir satırla eşleşiyor mu. */
    val foundAgent: AgentFound?
        get() {
            val ip = form.ip.trim()
            val mac = (Validation.parseMac(form.mac) as? Validation.MacResult.Valid)?.mac
            return scan.entries.firstOrNull { e -> e.agent != null && (e.ip == ip || (mac != null && e.mac == mac)) }?.agent
        }

    /** Spec 10.5 + K6 ipucu satırı; öncelik hata → agent → MAC boş → uyarı. */
    val hint: String
        get() {
            val agent = foundAgent
            return when {
                DeviceError.NAME_INVALID in errors || DeviceError.IP_INVALID in errors -> HINT_REQUIRED
                DeviceError.NAME_DUPLICATE in errors -> DeviceError.NAME_DUPLICATE.message
                DeviceError.MAC_DUPLICATE in errors -> DeviceError.MAC_DUPLICATE.message
                DeviceError.MAC_INVALID in errors -> DeviceError.MAC_INVALID.message
                savedAgent == SavedAgent.PAIRED_ELSEWHERE -> HINT_ELSEWHERE
                !isEdit && agent != null && agent.pairedElsewhere -> HINT_ELSEWHERE
                !isEdit && agent != null && agent.pairedToYou -> HINT_KNOWN
                !isEdit && agent != null -> HINT_AGENT
                form.mac.isBlank() -> HINT_NO_MAC
                DeviceWarning.IP_OUTSIDE_SUBNET in warnings -> DeviceWarning.IP_OUTSIDE_SUBNET.message
                isEdit -> "Değişiklikler kaydedilince hemen uygulanır"
                else -> "Hazır · eklenince durumu takip edilir"
            }
        }

    /** Önizleme kartının alt satırındaki agent bilgisi. */
    val agentLabel: String
        get() = when {
            savedAgent == SavedAgent.PAIRED -> "Agent ✓"
            savedAgent == SavedAgent.PAIRED_ELSEWHERE -> "Başka telefona eşli"
            foundAgent?.pairedElsewhere == true -> "Başka telefona eşli"
            foundAgent?.pairedToYou == true -> "Agent bu telefonu tanıyor"
            foundAgent != null -> "Agent bulundu"
            else -> "Agent yok"
        }

    companion object {
        const val HINT_REQUIRED = "Ad ve geçerli IP gerekli"
        const val HINT_NO_MAC = "MAC olmadan uyandırılamaz · PC açıkken otomatik bulunur"
        const val HINT_AGENT = "Agent bulundu · eklenince otomatik eşleşir"
        const val HINT_KNOWN = "Agent bu telefonu tanıyor · eklenince hemen bağlanır"
        const val HINT_ELSEWHERE = "Başka telefona eşli · PC'de `wolagent reset` çalıştır"
    }
}

/** Panelin tek seferlik sonuçları. */
sealed interface SheetResult {
    data class Saved(val id: Long, val added: Boolean) : SheetResult
    data class Deleted(val id: Long) : SheetResult
}

/**
 * Cihaz ekle/düzenle paneli (spec 10.5, 7.4, 15). Ekleme modunda açılınca tarama başlar, [close] ile iptal edilir.
 * Kaydedince discovery + otomatik eşleşme hemen tetiklenir (S05 AgentLinker akışı).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DeviceSheetViewModel @Inject constructor(
    private val repo: DeviceRepository,
    private val scanner: DeviceScanner,
    private val wifi: WifiStateSource,
    private val linker: AgentLinkTrigger,
) : ViewModel() {
    private val _state = MutableStateFlow(SheetUiState())
    val state: StateFlow<SheetUiState> = _state.asStateFlow()

    @Volatile private var devices: List<Device> = emptyList()
    private var scanJob: Job? = null

    /** Test ayarlar (0). */
    internal var scanStartDelayMs = SCAN_START_DELAY_MS
    private var session = 0

    init {
        viewModelScope.launch { repo.observeDevices().collect { devices = it } }
        // Doğrulama her form değişiminde (DB'ye bakar: aynı ad/MAC); eskisi iptal edilir.
        viewModelScope.launch {
            _state.map { Triple(it.form, it.mode, it.saving) }
                .distinctUntilChanged()
                .mapLatest { (form, mode, _) ->
                    val w = wifi.state.value
                    repo.validate(form.toInput(), (mode as? SheetMode.Edit)?.id, w.phoneIp, w.prefixLength)
                }
                .collect { v -> _state.update { it.copy(errors = v.errors, warnings = v.warnings) } }
        }
    }

    fun open(mode: SheetMode) {
        session++
        stopScan()
        _state.value = SheetUiState(mode = mode)
        when (mode) {
            // Tarama panel yerine oturduktan sonra başlar: soket/iş parçacığı patlaması giriş animasyonuyla çakışmasın.
            SheetMode.Add -> startScan(scanStartDelayMs)
            is SheetMode.Edit -> viewModelScope.launch {
                val d = repo.getDevice(mode.id) ?: return@launch
                val issue = linker.issues.value[d.id]
                _state.update {
                    it.copy(
                        form = SheetForm(d.name, d.ip, d.mac.orEmpty(), d.iconKey, d.colorIndex),
                        savedAgent = when {
                            issue == AgentIssue.PAIRED_ELSEWHERE -> SavedAgent.PAIRED_ELSEWHERE
                            d.agentId != null -> SavedAgent.PAIRED
                            else -> SavedAgent.NONE
                        },
                    )
                }
            }
        }
    }

    /** Panel kapanınca tarama iptal (spec 7.4). */
    fun close() {
        session++
        stopScan()
    }

    fun rescan() {
        if (_state.value.isEdit) return
        startScan()
    }

    fun edit(transform: SheetForm.() -> SheetForm) = _state.update { s ->
        val form = s.form.transform()
        // Elle IP/MAC değişince taramadan seçilen satır vurgusu düşer.
        val picked = s.pickedKey?.takeIf { key -> s.scan.entries.any { it.key == key && it.ip == form.ip.trim() } }
        s.copy(form = form, pickedKey = picked)
    }

    fun pick(entry: ScanEntry) {
        if (entry.added) return
        _state.update {
            it.copy(
                pickedKey = entry.key,
                form = it.form.copy(
                    name = entry.name.take(Validation.NAME_MAX),
                    ip = entry.ip,
                    mac = entry.mac.orEmpty(),
                    iconKey = entry.iconKey,
                ),
            )
        }
    }

    fun save(onResult: (SheetResult) -> Unit) {
        val s = _state.value
        if (!s.canSave) return
        val current = session
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val input = s.form.toInput()
            val result = when (val mode = s.mode) {
                SheetMode.Add -> repo.add(input)
                is SheetMode.Edit -> repo.update(mode.id, input)
            }
            when (result) {
                is SaveResult.Saved -> {
                    linker.linkNow()
                    // saving açık kalır: panel kapanırken yeni kayıt "Bu ad zaten kayıtlı" ipucu göstermesin.
                    if (current == session) onResult(SheetResult.Saved(result.id, added = s.mode == SheetMode.Add))
                }
                is SaveResult.Invalid -> _state.update { it.copy(saving = false, errors = result.errors) }
            }
        }
    }

    fun delete(onResult: (SheetResult) -> Unit) {
        val id = (_state.value.mode as? SheetMode.Edit)?.id ?: return
        viewModelScope.launch {
            val device = repo.getDevice(id)
            // K25: anahtar, agent'a UNPAIR gönderilene kadar tutulur; AgentLinker siler.
            repo.delete(id, keepAgentKey = true)
            device?.let(linker::release)
            onResult(SheetResult.Deleted(id))
        }
    }

    private fun startScan(delayMs: Long = 0) {
        stopScan()
        val current = session
        _state.update { it.copy(scan = it.scan.copy(running = true)) }
        scanJob = viewModelScope.launch {
            if (delayMs > 0) kotlinx.coroutines.delay(delayMs)
            // 253 host'luk boru hattı ve birleştirme ana iş parçacığında koşmasın (S07: panel ilk açılışta takılıyordu).
            scanner.scan(wifi.state.value) { devices }
                .flowOn(Dispatchers.Default)
                .conflate()
                .catch { e -> if (e is CancellationException) throw e else emit(_state.value.scan.copy(running = false)) }
                .collect { snap -> if (current == session) _state.update { it.copy(scan = snap) } }
        }
    }

    private fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _state.update { it.copy(scan = it.scan.copy(running = false)) }
    }

    override fun onCleared() = stopScan()

    private companion object {
        /** `smooth` giriş yayının (0.5 sn) oturma süresi. */
        const val SCAN_START_DELAY_MS = 450L
    }

    private fun SheetForm.toInput() = DeviceInput(name, ip, mac.ifBlank { null }, iconKey, colorIndex)
}
