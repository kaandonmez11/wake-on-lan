package com.kdgames.wake_on_lan.net

import android.net.Network
import kotlin.math.roundToInt

/** Telefonun bağlı olduğu Wi-Fi ağı (spec 7.1). `connected=false` → uygulama NoWifi kapısını gösterir. */
data class WifiState(
    val connected: Boolean = false,
    val network: Network? = null,
    /** Konum izni/servisi yoksa Android SSID'yi gizler → null (UI "Wi-Fi" yazar). */
    val ssid: String? = null,
    val rssi: Int? = null,
    /** 0–4 */
    val signalLevel: Int = 0,
    val linkSpeedMbps: Int? = null,
    val phoneIp: String? = null,
    val prefixLength: Int? = null,
    /** /31 ve /32'de ya da IP yokken null. */
    val broadcast: String? = null,
) {
    companion object {
        val Disconnected = WifiState()

        /** Android'in SSID gizlendiğinde döndürdüğü değer (`WifiManager.UNKNOWN_SSID`). */
        const val UNKNOWN_SSID = "<unknown ssid>"

        /** Platform callback'lerinden gelen ham değerlerden durum üretir. Saf; testlenir. */
        fun from(
            network: Network?,
            rawSsid: String?,
            rssi: Int?,
            level: Int?,
            maxLevel: Int?,
            linkSpeedMbps: Int?,
            ipv4: String?,
            prefixLength: Int?,
        ): WifiState = WifiState(
            connected = true,
            network = network,
            ssid = cleanSsid(rawSsid),
            rssi = rssi,
            signalLevel = normalizeLevel(level, maxLevel),
            linkSpeedMbps = linkSpeedMbps?.takeIf { it > 0 },
            phoneIp = ipv4,
            prefixLength = prefixLength,
            broadcast = if (ipv4 != null && prefixLength != null && prefixLength in 0..30) {
                runCatching { BroadcastResolver.compute(ipv4, prefixLength) }.getOrNull()
            } else null,
        )

        /** Tırnakları atar; gizli/boş SSID → null. */
        fun cleanSsid(raw: String?): String? {
            val s = raw?.trim()?.removeSurrounding("\"") ?: return null
            return s.takeUnless { it.isBlank() || it == UNKNOWN_SSID || it == "0x" }
        }

        /** Cihazın kendi ölçeğindeki seviyeyi (0..max) spec'teki 0–4 ölçeğine çevirir. */
        fun normalizeLevel(level: Int?, maxLevel: Int?): Int {
            if (level == null || maxLevel == null || maxLevel <= 0) return 0
            return (level.coerceIn(0, maxLevel) * 4f / maxLevel).roundToInt()
        }
    }
}

/** Wi-Fi bağlantısının (ağ ya da erişim noktası) son değişim anı; StatusEngine roaming toleransı için. */
fun interface WifiChangeTime {
    fun lastChangeMs(): Long
}
