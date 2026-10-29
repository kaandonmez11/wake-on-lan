package com.kdgames.wake_on_lan.net.scan

import com.kdgames.wake_on_lan.data.db.Device
import com.kdgames.wake_on_lan.net.BroadcastResolver
import com.kdgames.wake_on_lan.net.MacAddress
import com.kdgames.wake_on_lan.net.agent.AgentMessage
import com.kdgames.wake_on_lan.net.agent.NicSelector

/** Taramada bulunan agent bilgisi (DISCOVER_REPLY özeti). */
data class AgentFound(
    val agentId: String,
    val hostname: String,
    val paired: Boolean,
    val hibernate: Boolean,
    val sleepHibernates: Boolean,
    /** K25: bu telefona eşli (uygulama yeniden kurulmuş); eklenince agent tanır, reset gerekmez. */
    val pairedToYou: Boolean = false,
) {
    val pairedElsewhere: Boolean get() = paired && !pairedToYou
}

/**
 * "Ağda bulunanlar" satırı (spec 7.4). [key] satırın sabit kimliği (ilk görüldüğü IP): sonradan MAC gelse de
 * değişmez, böylece satır yeniden "pop" etmez.
 */
data class ScanEntry(
    val key: String,
    val ip: String,
    val mac: String?,
    val name: String,
    val iconKey: String,
    val agent: AgentFound?,
    /** Zaten ekli: soluk ve seçilemez. */
    val added: Boolean = false,
)

data class ScanSnapshot(val entries: List<ScanEntry> = emptyList(), val running: Boolean = false)

/**
 * Tarama sonuçlarını birleştirir (spec 7.4): hepsi IP'ye göre toplanır, sonra **MAC → yoksa IP** anahtarıyla
 * tekilleşir; agent'ın diğer kartlarının (ör. Wi-Fi) IP/MAC'leri agent satırına katılır. Satırlar geliş sırasında
 * kalır: yeni satır hep alta eklenir, üsttekiler yer değiştirmez. Saf, iş parçacığı güvenli.
 */
class ScanMerger(private val phoneIp: String?, private val prefixLength: Int?) {
    private class Host(val ip: String) {
        var ports: Set<Int> = emptySet()
        var mac: String? = null
        var netBiosName: String? = null
        var mdnsName: String? = null
        var dnsName: String? = null
        var agent: AgentMessage.DiscoverReply? = null
    }

    private val hosts = LinkedHashMap<String, Host>()

    @Synchronized
    fun addAgent(host: String, reply: AgentMessage.DiscoverReply) {
        val h = hosts.getOrPut(host) { Host(host) }
        h.agent = reply
        NicSelector.select(reply.nics, phoneIp, prefixLength)?.mac?.let(MacAddress::normalize)?.let { h.mac = it }
    }

    @Synchronized
    fun addAlive(ip: String, acceptedPorts: Set<Int>) {
        val h = hosts.getOrPut(ip) { Host(ip) }
        h.ports = h.ports + acceptedPorts
    }

    @Synchronized
    fun addNetBios(ip: String, hostname: String?, mac: String?) {
        val h = hosts.getOrPut(ip) { Host(ip) }
        hostname?.takeIf { it.isNotBlank() }?.let { h.netBiosName = it }
        if (h.mac == null) mac?.let(MacAddress::normalize)?.let { h.mac = it }
    }

    @Synchronized
    fun addMdns(ip: String, name: String?, mac: String?) {
        val h = hosts.getOrPut(ip) { Host(ip) }
        name?.takeIf { it.isNotBlank() }?.let { h.mdnsName = it }
        if (h.mac == null) mac?.let(MacAddress::normalize)?.let { h.mac = it }
    }

    @Synchronized
    fun addDns(ip: String, name: String?) {
        hosts[ip]?.let { h -> name?.takeIf { it.isNotBlank() && it != ip }?.let { h.dnsName = it } }
    }

    @Synchronized
    fun snapshot(existing: List<Device>, running: Boolean): ScanSnapshot {
        // Agent'ın diğer kartlarına ait IP ve MAC'ler ayrı satır olmasın.
        val absorbedIps = mutableSetOf<String>()
        val absorbedMacs = mutableSetOf<String>()
        hosts.values.filter { it.agent != null }.forEach { h ->
            h.agent!!.nics.forEach { nic ->
                if (nic.ip.isNotBlank() && nic.ip != h.ip) absorbedIps += nic.ip
                MacAddress.normalize(nic.mac)?.let { if (it != h.mac) absorbedMacs += it }
            }
        }
        val byKey = LinkedHashMap<String, Host>()
        hosts.values
            .filter { it.ip != phoneIp && it.ip !in absorbedIps && (it.agent != null || it.mac !in absorbedMacs) }
            .forEach { h ->
                val key = h.mac ?: h.ip
                val current = byKey[key]
                // Aynı MAC iki IP'de: önce görülen kalır; agent'lı olan gelirse onun yerine geçer.
                if (current == null || (current.agent == null && h.agent != null)) byKey[key] = h
            }
        val entries = byKey.values.map { it.toEntry(existing) }
        return ScanSnapshot(entries, running)
    }

    private fun Host.toEntry(existing: List<Device>): ScanEntry {
        val a = agent
        val name = a?.hostname ?: netBiosName ?: mdnsName ?: dnsName ?: ip
        val added = existing.any { d -> (mac != null && d.mac.equals(mac, ignoreCase = true)) || d.ip == ip }
        return ScanEntry(
            key = ip,
            ip = ip,
            mac = mac,
            name = name,
            iconKey = guessIcon(a != null, ports),
            agent = a?.let { AgentFound(it.agentId, it.hostname, it.paired, it.hibernate, it.sleepHibernates, it.pairedToYou) },
            added = added,
        )
    }

    companion object {
        /** Spec 7.4 madde 5: agent/3389 → pc; 5000+445 → nas; 8001/8002 → tv; diğer → pc. */
        fun guessIcon(hasAgent: Boolean, ports: Set<Int>): String = when {
            hasAgent || 3389 in ports -> "pc"
            5000 in ports && 445 in ports -> "nas"
            8001 in ports || 8002 in ports -> "tv"
            else -> "pc"
        }

        /**
         * Taranacak adresler: prefix ≥ 24 ise alt ağın tüm host'ları, değilse telefonun /24'ü (≤ 254 adres).
         * Telefonun kendi IP'si hariç.
         */
        fun hostsToScan(phoneIp: String, prefixLength: Int): List<String> {
            val ip = BroadcastResolver.parse(phoneIp)
            val prefix = prefixLength.coerceIn(24, 30)
            val mask = -1 shl (32 - prefix)
            val network = ip and mask
            val broadcast = network or mask.inv()
            return ((network + 1) until broadcast).map(BroadcastResolver::format).filter { it != phoneIp }
        }
    }
}
