package com.kdgames.wake_on_lan.domain.status

import com.kdgames.wake_on_lan.data.crypto.KeyVault
import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.data.prefs.SettingsStore
import com.kdgames.wake_on_lan.domain.model.RoundSignals
import com.kdgames.wake_on_lan.net.NoWifiException
import com.kdgames.wake_on_lan.net.agent.AgentClient
import com.kdgames.wake_on_lan.net.agent.AgentErrorCode
import com.kdgames.wake_on_lan.net.agent.AgentResult
import com.kdgames.wake_on_lan.net.agent.AgentSession
import com.kdgames.wake_on_lan.net.probe.IcmpProbe
import com.kdgames.wake_on_lan.net.probe.NetBiosProbe
import com.kdgames.wake_on_lan.net.probe.TcpProbe
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/** Bir cihaz için S1–S4'ü paralel çalıştırır. @throws NoWifiException (tur sayılmaz). */
fun interface SignalProber {
    suspend fun probe(device: Device): RoundSignals
}

/** Agent oturumunu KeyVault'tan açar; eşleşme yoksa null. */
@Singleton
class AgentSessions @Inject constructor(private val vault: KeyVault, private val settings: SettingsStore) {
    // Tur başına (her 3 sn, her cihaz) Keystore dosyası okuyup çözmemek için küçük önbellek.
    // Takma ad değişince (yeniden eşleşme) yeni anahtar okunur; eski girdiler LRU ile düşer.
    private val keys = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > 16
    }
    @Volatile private var phoneId: String? = null

    suspend fun of(device: Device): AgentSession? {
        val agentId = device.agentId ?: return null
        val alias = device.agentKeyAlias ?: return null
        val key = synchronized(keys) { keys[alias] } ?: vault.load(alias)?.also { synchronized(keys) { keys[alias] = it } } ?: return null
        val id = phoneId ?: settings.ensurePhoneId().also { phoneId = it }
        return AgentSession(agentId, id, key)
    }

    /** Anahtar silinince (K6, cihaz silme) önbellekten de düşer. */
    fun forget(alias: String?) {
        if (alias != null) synchronized(keys) { keys.remove(alias) }
    }
}

@Singleton
class NetworkSignalProber @Inject constructor(
    private val agent: AgentClient,
    private val sessions: AgentSessions,
    private val netBios: NetBiosProbe,
    private val tcp: TcpProbe,
    private val icmp: IcmpProbe,
) : SignalProber {

    override suspend fun probe(device: Device): RoundSignals = coroutineScope {
        val session = sessions.of(device)
        val s1 = session?.let {
            async {
                when (val r = agent.status(device.ip, it)) {
                    is AgentResult.Ok -> RoundSignals.AgentSignal(true, r.elapsedMs, r.value.uptimeSec, r.value.display)
                    AgentResult.UnknownPhone -> RoundSignals.AgentSignal(false, 0, error = AgentErrorCode.UNKNOWN_PHONE)
                    AgentResult.BadSignature -> RoundSignals.AgentSignal(false, 0, error = AgentErrorCode.BAD_SIG)
                    is AgentResult.Error -> RoundSignals.AgentSignal(false, 0, error = r.code)
                    AgentResult.NoResponse -> RoundSignals.AgentSignal(false, 0)
                }
            }
        }
        val s2 = async { netBios.probe(device.ip)?.let { RoundSignals.NetBiosSignal(it.mac, it.hostname, it.elapsedMs) } }
        val s3 = async { tcp.probe(device.ip, device.probePorts.ifEmpty { Device.DEFAULT_PROBE_PORTS }).let { RoundSignals.TcpSignal(it.alive, it.accepted, it.elapsedMs) } }
        val s4 = async {
            when (val r = icmp.probe(device.ip)) {
                is IcmpProbe.Result.Reply -> RoundSignals.IcmpSignal(true, r.ms)
                is IcmpProbe.Result.NoReply -> RoundSignals.IcmpSignal(false, r.elapsedMs)
                is IcmpProbe.Result.Unavailable -> RoundSignals.IcmpSignal(false, 0, unavailable = true)
            }
        }
        RoundSignals(s1?.await(), s2.await(), s3.await(), s4.await())
    }
}
