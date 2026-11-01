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
import com.kdgames.wake_on_lan.net.KnownNetworks
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

    /** [known]: ağ imzası → SSID (ön planda öğrenilen, KnownNetworks). */
    fun resolve(ssid: String?, fingerprint: String?, known: Map<String, String>): Resolved {
        WifiState.cleanSsid(ssid)?.let { return Resolved(it, Source.SSID) }
        if (fingerprint != null) known[fingerprint]?.let { return Resolved(it, Source.FINGERPRINT) }
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
class WifiTriggerLog @Inject constructor(@ApplicationContext context: Context, private val knownNetworks: KnownNetworks) {
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

    /** Ağ imzası → SSID (ön planda öğrenilen). */
    fun known(): Map<String, String> = knownNetworks.all()

    /** Açık bir "eve gelince" zamanlayıcısı var mı. */
    var wanted: Boolean
        get() = prefs.getBoolean(KEY_WANTED, false)
        set(value) = prefs.edit().putBoolean(KEY_WANTED, value).apply()

    /** Sistem kaydı kurulu mu (her teslimattan sonra sistem siler). Debug'da gösterilir. */
    var armed: Boolean
        get() = prefs.getBoolean(KEY_ARMED, false)
        set(value) = prefs.edit().putBoolean(KEY_ARMED, value).apply()

    /** Aynı bağlantı için ikinci kez karar verilmez (kayıt anındaki yankı, iki yoldan gelen aynı olay). */
    @Synchronized
    fun markHandled(handle: Long): Boolean {
        if (prefs.getLong(KEY_HANDLE, 0) == handle) return false
        prefs.edit().putLong(KEY_HANDLE, handle).commit()
        return true
    }

    private companion object {
        const val KEY_LOG = "log"
        const val KEY_HANDLE = "lastHandle"
        const val KEY_WANTED = "wanted"
        const val KEY_ARMED = "armed"
        const val MAX = 30
    }
}

/**
 * "Eve gelince" algılaması (spec 9.4), iki yoldan:
 * 1. `registerNetworkCallback(request, PendingIntent)`: süreç ölüyken de sistem [WifiTriggerReceiver]'ı çağırır.
 *    Android bu kaydı **her teslimattan sonra kendisi siler** (S09 cihaz testi, `dumpsys connectivity`); Wi-Fi'dayken
 *    yeniden kaydolmak hemen bir yankı teslimatı doğurur. Bu yüzden kayıt **Wi-Fi'dan çıkınca** kurulur ("kurulu"):
 *    süreç canlıyken bağlantı kopar kopmaz, değilse en geç 15 dk'da bir ([WifiTriggerKeeper]) ve açılışta.
 * 2. Süreç canlıyken NetworkMonitor'ın gördüğü her yeni Wi-Fi bağlantısı doğrudan işlenir.
 * Aynı bağlantı tek kez işlenir (ağ tanıtıcısı). Zamanlayıcıyı evde kurmak onu hemen çalıştırmaz.
 */
@Singleton
class WifiTriggerRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val monitor: NetworkMonitor,
    private val log: WifiTriggerLog,
    @AppScope private val scope: CoroutineScope,
) : WifiTriggerSync {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private var synced: Boolean? = null

    init {
        scope.launch {
            // Bu süreçte görülen ilk bağlantı "eve gelmek" değildir (zaten bağlıydık); sonrakiler canlı geçiştir.
            var seen = false
            var last: Network? = null
            monitor.state.collect { s ->
                val n = s.network?.takeIf { s.connected && s.phoneIp != null }
                if (!s.connected) {
                    // Wi-Fi koptu: bir sonraki bağlanış için sistem kaydını kur.
                    // Kopan ağ birkaç sn listede kalabilir: kısa aralarla dene.
                    if (last != null && log.wanted) scope.launch {
                        for (wait in listOf(1_500L, 5_000L, 15_000L)) {
                            kotlinx.coroutines.delay(wait)
                            if (monitor.state.value.connected) break
                            arm("Wi-Fi koptu")
                            if (log.armed) break
                        }
                    }
                    last = null
                    return@collect
                }
                if (n == null || n == last) return@collect
                last = n
                if (!seen) {
                    seen = true
                    return@collect
                }
                if (log.wanted && log.markHandled(n.networkHandle)) {
                    scope.launch { runCatching { WifiArrival.handle(context, context.scheduleDeps(), n, "uygulama açıkken") } }
                }
            }
        }
    }

    @Synchronized
    override fun sync(needed: Boolean, force: Boolean) {
        if (synced == needed && !force) return
        val wasWanted = log.wanted
        log.wanted = needed
        synced = needed
        if (!needed) {
            runCatching { connectivity.unregisterNetworkCallback(pending()) }
            log.armed = false
            WifiTriggerKeeper.cancel(context)
            return
        }
        WifiTriggerKeeper.schedule(context)
        // Açılışta sistem kayıtları silinmiştir.
        if (force) log.armed = false
        val wifiNow = wifiNetworkNow()
        if (wifiNow != null) {
            // Yeni kurulan zamanlayıcı (evdeyken) ya da açılış: şu anki bağlantı "eve gelmek" sayılmaz.
            if (!wasWanted || force) log.markHandled(wifiNow.networkHandle)
            return
        }
        arm(if (force) "açılış" else "kurulum")
    }

    /** Wi-Fi'da değilken sistem kaydını kurar (yankı olmaz). Keeper ve "Wi-Fi koptu" çağırır. */
    @Synchronized
    fun arm(reason: String) {
        if (!log.wanted || wifiNetworkNow() != null) return
        runCatching { connectivity.unregisterNetworkCallback(pending()) }
        runCatching {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivity.registerNetworkCallback(request, pending())
        }.onSuccess {
            if (!log.armed) log.add("Kuruldu ($reason): bir sonraki Wi-Fi bağlantısı beklenecek")
            log.armed = true
        }.onFailure { log.add("Kurulamadı: ${it.javaClass.simpleName}") }
    }

    /** Sistem kaydı teslim edip sildi. */
    fun delivered() {
        log.armed = false
    }

    @Suppress("DEPRECATION")
    private fun wifiNetworkNow(): Network? = runCatching {
        connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

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

/** 15 dk'da bir: Wi-Fi'da değilsek "eve gelince" kaydını kurar (yalnız açık WIFI zamanlayıcısı varken). */
class WifiTriggerKeeper(context: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        applicationContext.scheduleDeps().wifiTrigger().arm("15 dk kontrolü")
        return Result.success()
    }

    companion object {
        private const val NAME = "wifi-trigger-keeper"

        fun schedule(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<WifiTriggerKeeper>(15, java.util.concurrent.TimeUnit.MINUTES).build()
            runCatching { androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, request) }
        }

        fun cancel(context: Context) {
            runCatching { androidx.work.WorkManager.getInstance(context).cancelUniqueWork(NAME) }
        }
    }
}

/** Wi-Fi bağlandı (PendingIntent callback): SSID eşleşen "eve gelince" zamanlayıcıları çalışır. */
class WifiTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java) ?: return
        val deps = context.scheduleDeps()
        // Sistem kaydı bu teslimattan sonra siler; Wi-Fi kopunca yeniden kurulur.
        deps.wifiTrigger().delivered()
        val fresh = deps.wifiLog().markHandled(network.networkHandle)
        if (!fresh) return
        val pending = goAsync()
        deps.scope().launch {
            try {
                withTimeoutOrNull(8_000) { WifiArrival.handle(context, deps, network, "sistem") }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Yeni Wi-Fi bağlantısını işler: SSID'yi bulur, eşleşen zamanlayıcıları çalıştırır, günlüğe yazar. */
internal object WifiArrival {
    suspend fun handle(context: Context, deps: ScheduleEntryPoint, network: Network, via: String) {
        val log = deps.wifiLog()
        // NetworkMonitor (konum bayraklı callback) bu ağı birkaç yüz ms içinde yayınlar.
        val state = withTimeoutOrNull(4_000) { deps.monitor().state.first { it.network == network && it.phoneIp != null } }
        val raw = if (state?.ssidGuessed == true) null else state?.ssid ?: runCatching {
            (context.getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(network)?.transportInfo as? WifiInfo)?.ssid
        }.getOrNull()
        val resolved = WifiTriggerRules.resolve(raw, state?.fingerprint, log.known())
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
        log.add("Bağlandı ($via): ${resolved.ssid ?: "?"} · $how → ${results.joinToString(", ").ifEmpty { "zamanlayıcı yok" }}")
    }
}
