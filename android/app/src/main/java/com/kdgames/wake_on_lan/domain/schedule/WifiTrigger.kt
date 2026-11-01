package com.kdgames.wake_on_lan.domain.schedule

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import com.kdgames.wake_on_lan.data.db.Schedule
import com.kdgames.wake_on_lan.domain.model.ScheduleType
import com.kdgames.wake_on_lan.net.AppScope
import com.kdgames.wake_on_lan.net.NetworkMonitor
import com.kdgames.wake_on_lan.net.WifiState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Eve gelince" kararları (spec 9.4); saf, testli.
 *
 * Android SSID'yi konum izniyle ve çoğunlukla yalnız ön planda verir; arka planda (PendingIntent callback'i)
 * gizli gelebilir. Bu yüzden uygulama ön plandayken her SSID için **ağ imzası** öğrenilir (ağ geçidi + DHCP
 * sunucusu + alt ağ); SSID okunamazsa ağ imzasından bulunur.
 */
object WifiTriggerRules {
    /** Aynı zamanlayıcı 10 dk içinde tekrar tetiklenmez. */
    const val LOCK_MS = 10 * 60_000L

    enum class Source { SSID, FINGERPRINT, NONE }

    data class Resolved(val ssid: String?, val source: Source)

    /** [known]: SSID → ağ imzası (ön planda öğrenilen). */
    fun resolve(ssid: String?, fingerprint: String?, known: Map<String, String>): Resolved {
        WifiState.cleanSsid(ssid)?.let { return Resolved(it, Source.SSID) }
        if (fingerprint != null) known.entries.firstOrNull { it.value == fingerprint }?.let { return Resolved(it.key, Source.FINGERPRINT) }
        return Resolved(null, Source.NONE)
    }

    enum class Decision { FIRE, NOT_WIFI, DISABLED, OTHER_NETWORK, LOCKED }

    /** [lastRunAt]: zamanlayıcının son çalışmasının anı (atlanan dahil). */
    fun decide(s: Schedule, ssid: String?, now: Long, lastRunAt: Long?): Decision = when {
        s.type != ScheduleType.WIFI_CONNECT -> Decision.NOT_WIFI
        !s.enabled -> Decision.DISABLED
        ssid == null || s.ssid?.trim() != ssid -> Decision.OTHER_NETWORK
        lastRunAt != null && now - lastRunAt in 0 until LOCK_MS -> Decision.LOCKED
        else -> Decision.FIRE
    }

    fun label(d: Decision): String = when (d) {
        Decision.FIRE -> "tetiklendi"
        Decision.NOT_WIFI -> "saat zamanlayıcısı"
        Decision.DISABLED -> "kapalı"
        Decision.OTHER_NETWORK -> "başka ağ"
        Decision.LOCKED -> "10 dk kilidi"
    }
}

/** Debug › Zamanlayıcı: "eve gelince" günlüğü (son 30 satır; süreç ölse de kalır). */
@Singleton
class WifiTriggerLog @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("wifi_trigger", Context.MODE_PRIVATE)
    private val _lines = MutableStateFlow(read())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()
    private val time = SimpleDateFormat("dd.MM HH:mm:ss", Locale.forLanguageTag("tr"))

    @Synchronized
    fun add(text: String) {
        val list = (listOf("${time.format(Date())} · $text") + read()).take(MAX)
        prefs.edit().putString(KEY_LOG, list.joinToString("\n")).apply()
        _lines.value = list
    }

    private fun read(): List<String> = prefs.getString(KEY_LOG, null)?.split("\n")?.filter { it.isNotBlank() }.orEmpty()

    /** SSID → ağ imzası. */
    fun known(): Map<String, String> = prefs.getStringSet(KEY_KNOWN, emptySet()).orEmpty()
        .mapNotNull { e -> e.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

    @Synchronized
    fun learn(ssid: String, fingerprint: String) {
        val current = known()
        if (current[ssid] == fingerprint) return
        val next = (current + (ssid to fingerprint)).entries.toList().takeLast(MAX_KNOWN).map { "${it.key}\t${it.value}" }.toSet()
        prefs.edit().putStringSet(KEY_KNOWN, next).apply()
    }

    /** Callback kaydı önceki süreçten beri var mı. */
    var registered: Boolean
        get() = prefs.getBoolean(KEY_REGISTERED, false)
        set(value) = prefs.edit().putBoolean(KEY_REGISTERED, value).apply()

    /** Aynı bağlantı için ikinci kez karar verilmez (kayıt anındaki yankı, yetenek değişimleri). */
    @Synchronized
    fun markHandled(handle: Long): Boolean {
        if (prefs.getLong(KEY_HANDLE, 0) == handle) return false
        prefs.edit().putLong(KEY_HANDLE, handle).apply()
        return true
    }

    private companion object {
        const val KEY_LOG = "log"
        const val KEY_KNOWN = "known"
        const val KEY_HANDLE = "lastHandle"
        const val KEY_REGISTERED = "registered"
        const val MAX = 30
        const val MAX_KNOWN = 8
    }
}

/**
 * `registerNetworkCallback(request, PendingIntent)` kaydı (spec 9.4). Açık WIFI zamanlayıcısı varken kayıtlı;
 * süreç ölse de sistem Wi-Fi bağlanınca [WifiTriggerReceiver]'ı çağırır. Kayıt anında bağlı olunan ağ
 * "görüldü" sayılır: zamanlayıcıyı evde kurmak onu hemen tetiklemez.
 */
@Singleton
class WifiTriggerRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: NetworkMonitor,
    private val log: WifiTriggerLog,
    @AppScope scope: CoroutineScope,
) : WifiTriggerSync {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var registered: Boolean? = null

    init {
        // Ön planda SSID okunabilirken ağ imzası öğrenilir.
        scope.launch {
            monitor.state.collect { s ->
                val ssid = s.ssid
                val fp = s.fingerprint
                if (ssid != null && fp != null) log.learn(ssid, fp)
            }
        }
    }

    @Synchronized
    override fun sync(needed: Boolean, force: Boolean) {
        if (registered == needed && !force) return
        runCatching { connectivity.unregisterNetworkCallback(pending()) }
        if (needed) {
            // Yeni kurulan zamanlayıcı ya da açılış: o an bağlı olunan ağ "eve gelmek" sayılmaz. Süreç yeniden
            // başlarken (kayıt zaten vardı) işaretlenmez; yoksa alıcının işleyeceği bağlantı yarışta kaybolur.
            if (force || !log.registered) monitor.state.value.network?.let { log.markHandled(it.networkHandle) }
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, pending())
        }
        registered = needed
        log.registered = needed
    }

    private fun pending(): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, WifiTriggerReceiver::class.java).setAction(ACTION),
        // Sistem EXTRA_NETWORK ekler → mutable (açık bileşenli intent).
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION = "com.kdgames.wake_on_lan.WIFI_CONNECTED"
        private const val REQUEST_CODE = 9_001
    }
}

/** Wi-Fi bağlandı (PendingIntent callback): SSID eşleşen "eve gelince" zamanlayıcıları çalışır. */
class WifiTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java) ?: return
        val deps = context.scheduleDeps()
        val log = deps.wifiLog()
        if (!log.markHandled(network.networkHandle)) return
        val pending = goAsync()
        deps.scope().launch {
            try {
                withTimeoutOrNull(8_000) { handle(context, deps, network) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, deps: ScheduleEntryPoint, network: Network) {
        val log = deps.wifiLog()
        // NetworkMonitor (konum bayraklı callback) bu ağı birkaç yüz ms içinde yayınlar.
        val state = withTimeoutOrNull(4_000) { deps.monitor().state.first { it.network == network && it.phoneIp != null } }
        val rawSsid = state?.ssid ?: runCatching {
            (context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)?.transportInfo as? WifiInfo)?.ssid
        }.getOrNull()
        val resolved = WifiTriggerRules.resolve(rawSsid, state?.fingerprint, log.known())
        val how = when (resolved.source) {
            WifiTriggerRules.Source.SSID -> "SSID okundu"
            WifiTriggerRules.Source.FINGERPRINT -> "SSID gizli · ağ imzasından"
            WifiTriggerRules.Source.NONE -> "SSID okunamadı"
        }
        val now = System.currentTimeMillis()
        val results = deps.repo().getSchedules().filter { it.type == ScheduleType.WIFI_CONNECT }.map { s ->
            val decision = WifiTriggerRules.decide(s, resolved.ssid, now, deps.repo().latestRun(s.id)?.occurrence)
            if (decision == WifiTriggerRules.Decision.FIRE) ScheduleDispatch.start(context, deps, s, now)
            "#${s.id} ${WifiTriggerRules.label(decision)}"
        }
        log.add("Bağlandı: ${resolved.ssid ?: "?"} ($how) → ${results.joinToString(", ").ifEmpty { "zamanlayıcı yok" }}")
    }
}
