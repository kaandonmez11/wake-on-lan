package com.kdgames.wake_on_lan.net.scan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.net.IoDispatcher
import com.kdgames.wake_on_lan.net.MulticastGuard
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.SocketFactory
import com.kdgames.wake_on_lan.net.WifiState
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.agent.AgentMessage
import com.kdgames.wake_on_lan.net.probe.NetBiosProbe
import com.kdgames.wake_on_lan.net.probe.TcpProbe
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

/** Tarama kaynakları (testte sahte). */
interface ScanSources {
    suspend fun agents(broadcast: String): List<Pair<String, AgentMessage.DiscoverReply>>

    /** Canlıysa kabul eden portlar (refused da canlı sayılır → boş küme); yanıt yoksa null. */
    suspend fun ports(ip: String, ports: List<Int>, timeoutMs: Int): Set<Int>?

    suspend fun netBios(ip: String): Pair<String?, String?>?

    suspend fun reverseDns(ip: String): String?

    /** mDNS isimleri: (ip, ad, mac?) akışı; tarama boyunca toplanır. */
    fun mdns(): Flow<Triple<String, String?, String?>>
}

/**
 * Spec 7.4 (+ K11): agent discovery, /24 sweep (≤ 64 eşzamanlı, 300 ms), canlılara NetBIOS, mDNS ve ters DNS
 * hepsi paralel; sonuçlar [ScanMerger] ile birleşir ve **artan** anlık görüntüler olarak akar. Akış iptal edilince
 * (panel kapanınca) her şey durur. Tarama boyunca MulticastLock tutulur.
 */
@Singleton
class NetworkScanner @Inject constructor(
    private val sources: ScanSources,
    private val multicast: MulticastGuard,
) : DeviceScanner {
    override fun scan(wifi: WifiState, existing: () -> List<Device>): Flow<ScanSnapshot> = channelFlow {
        val phoneIp = wifi.phoneIp
        val prefix = wifi.prefixLength
        val broadcast = wifi.broadcast
        if (!wifi.connected || phoneIp == null || prefix == null || broadcast == null) {
            send(ScanSnapshot(running = false))
            return@channelFlow
        }
        val merger = ScanMerger(phoneIp, prefix)
        fun publish(running: Boolean = true) {
            trySend(merger.snapshot(existing(), running))
        }
        send(ScanSnapshot(running = true))
        multicast.withLock {
            coroutineScope {
                val mdnsJob = launch {
                    withTimeoutOrNull(MDNS_WINDOW_MS) {
                        sources.mdns().collect { (ip, name, mac) -> merger.addMdns(ip, name, mac); publish() }
                    }
                }
                val agentJob = launch {
                    runCatching { sources.agents(broadcast) }.getOrDefault(emptyList()).forEach { (host, reply) ->
                        merger.addAgent(host, reply)
                    }
                    publish()
                }
                val gate = Semaphore(MAX_PARALLEL)
                val sweep = ScanMerger.hostsToScan(phoneIp, prefix).map { ip ->
                    launch {
                        val accepted = gate.withPermit { safely { sources.ports(ip, SWEEP_PORTS, SWEEP_TIMEOUT_MS) } } ?: return@launch
                        merger.addAlive(ip, accepted)
                        publish()
                        launch { safely { sources.netBios(ip) }?.let { (name, mac) -> merger.addNetBios(ip, name, mac); publish() } }
                        launch { safely { sources.reverseDns(ip) }?.let { merger.addDns(ip, it); publish() } }
                    }
                }
                (sweep + agentJob).joinAll()
                mdnsJob.join()
            }
        }
        send(merger.snapshot(existing(), running = false))
    }

    private suspend fun <T> safely(block: suspend () -> T?): T? = try {
        block()
    } catch (e: NoWifiException) {
        throw e
    } catch (_: IOException) {
        null
    }

    companion object {
        /** Spec 7.4 + K11 (8001/8002: TV tahmini). */
        val SWEEP_PORTS = listOf(445, 3389, 22, 80, 443, 5000, 8080, 8001, 8002)
        const val SWEEP_TIMEOUT_MS = 300
        const val MAX_PARALLEL = 64
        const val MDNS_WINDOW_MS = 6_000L
    }
}

/** Gerçek kaynaklar: tüm soketler Wi-Fi'a bağlı (SocketFactory, spec 7.1). */
class AndroidScanSources @Inject constructor(
    private val agent: AgentClient,
    private val sockets: SocketFactory,
    private val netBiosProbe: NetBiosProbe,
    private val mdnsResolver: MdnsResolver,
    private val phone: com.kdgames.wake_on_lan.domain.agent.PhoneIdentity,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ScanSources {
    override suspend fun agents(broadcast: String) = agent.discover(broadcast, phone.keys.phoneId).map { it.host to it.reply }

    // 64 host × 9 port aynı anda bekleyebilsin diye IO havuzunun geniş bir görünümü; yoksa ölü adreslerin
    // 300 ms zaman aşımları 64 iş parçacığında sıraya girip tarama ~10 sn sürer.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val sweepIo = io.limitedParallelism(SWEEP_THREADS)

    override suspend fun ports(ip: String, ports: List<Int>, timeoutMs: Int): Set<Int>? = coroutineScope {
        val alive = java.util.concurrent.atomic.AtomicBoolean(false)
        val accepted = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
        ports.map { port ->
            launch(sweepIo) {
                val socket = sockets.tcp()
                try {
                    socket.connect(InetSocketAddress(ip, port), timeoutMs)
                    accepted += port
                    alive.set(true)
                } catch (e: ConnectException) {
                    if (TcpProbe.isRefused(e)) alive.set(true)
                } catch (_: IOException) {
                } finally {
                    runCatching { socket.close() }
                }
            }
        }.joinAll()
        if (alive.get()) accepted.toSet() else null
    }

    override suspend fun netBios(ip: String) = netBiosProbe.probe(ip)?.let { it.hostname to it.mac }

    override suspend fun reverseDns(ip: String): String? = withTimeoutOrNull(1_000) {
        runInterruptible(io) {
            val bytes = ip.split('.').map { it.toInt().toByte() }.toByteArray()
            InetAddress.getByAddress(bytes).canonicalHostName.takeIf { it != ip }?.substringBefore('.')
        }
    }

    override fun mdns() = mdnsResolver.discover()

    private companion object {
        const val SWEEP_THREADS = 160
    }
}

/**
 * mDNS (spec 7.4): `_workstation._tcp` ve `_smb._tcp` hizmetleri. `_workstation` adları çoğunlukla
 * "ad [aa:bb:cc:dd:ee:ff]" biçimindedir; MAC de buradan alınır. Android 17'de yerel ağ izni gerekir (S03).
 */
@Singleton
class MdnsResolver @Inject constructor(@ApplicationContext private val context: Context) {
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val executor = Executor { it.run() }

    fun discover(): Flow<Triple<String, String?, String?>> {
        val manager = nsd ?: return emptyFlow()
        return callbackFlow {
            val infoCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()
            val listeners = SERVICE_TYPES.map { type ->
                object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(service: NsdServiceInfo) {
                        val cb = object : NsdManager.ServiceInfoCallback {
                            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                            override fun onServiceUpdated(info: NsdServiceInfo) {
                                val (name, mac) = parseName(info.serviceName)
                                info.hostAddresses.filterIsInstance<java.net.Inet4Address>().forEach { addr ->
                                    trySend(Triple(addr.hostAddress.orEmpty(), name, mac))
                                }
                            }
                            override fun onServiceLost() = Unit
                            override fun onServiceInfoCallbackUnregistered() = Unit
                        }
                        runCatching { manager.registerServiceInfoCallback(service, executor, cb) }.onSuccess { synchronized(infoCallbacks) { infoCallbacks += cb } }
                    }
                    override fun onServiceLost(service: NsdServiceInfo) = Unit
                    override fun onDiscoveryStarted(serviceType: String) = Unit
                    override fun onDiscoveryStopped(serviceType: String) = Unit
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                }.also { runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, it) } }
            }
            awaitClose {
                synchronized(infoCallbacks) { infoCallbacks.forEach { runCatching { manager.unregisterServiceInfoCallback(it) } } }
                listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
            }
        }
    }

    companion object {
        val SERVICE_TYPES = listOf("_workstation._tcp", "_smb._tcp")
        private val MAC_IN_NAME = Regex("""\s*\[([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})]\s*$""")

        /** "OFIS-PC [d8:bb:c1:0a:2f:91]" → ("OFIS-PC", "d8:bb:…"). */
        fun parseName(serviceName: String): Pair<String, String?> {
            val m = MAC_IN_NAME.find(serviceName)
            return if (m != null) serviceName.substring(0, m.range.first).trim() to m.groupValues[1] else serviceName.trim() to null
        }
    }
}
