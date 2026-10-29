package com.kdgames.wake_on_lan.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.net.Inet4Address
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi ağını takip eder (spec 7.1). İnternet olup olmaması önemsiz; INTERNET yeteneği aranmaz.
 * SSID için callback `FLAG_INCLUDE_LOCATION_INFO` ile kaydedilir; konum izni/servisi yoksa SSID null kalır.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    @AppScope scope: CoroutineScope,
) : WifiChangeTime {
    @Volatile private var lastNetwork: Network? = null
    @Volatile private var lastBssid: String? = null
    @Volatile private var changedAt: Long = Long.MIN_VALUE / 2

    /** Wi-Fi ağı ya da bağlı erişim noktası (mesh node, BSSID) en son ne zaman değişti (monoton ms). */
    override fun lastChangeMs(): Long = changedAt

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private val restarts = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<WifiState> = restarts
        .flatMapLatest { wifiUpdates() }
        .stateIn(scope, SharingStarted.Eagerly, WifiState.Disconnected)

    /**
     * Callback'i yeniden kaydeder. İzin verildikten ya da konum servisi açıldıktan sonra çağrılır;
     * Android var olan callback'e SSID'yi kendiliğinden yeniden göndermez.
     */
    fun refresh() = restarts.update { it + 1 }

    private fun wifiUpdates() = callbackFlow {
        var current: Network? = null
        var caps: NetworkCapabilities? = null
        var link: LinkProperties? = null

        fun publish() {
            val network = current
            if (network == null) {
                trySend(WifiState.Disconnected)
                return
            }
            val info = caps?.transportInfo as? WifiInfo
            // Mesh/roaming: aynı SSID'de başka node'a geçiş kısa bir kesinti yaratır; StatusEngine bu
            // anı bilip birkaç saniye "kapalı" kararı vermez (S06 cihaz testi: iki routerlı ev).
            val bssid = info?.bssid
            if (network != lastNetwork || (bssid != null && bssid != lastBssid)) {
                changedAt = Clock.Monotonic.nowMs()
                lastNetwork = network
                if (bssid != null) lastBssid = bssid
            }
            val v4 = link?.linkAddresses?.firstOrNull { it.address is Inet4Address }
            val rssi = info?.rssi?.takeIf { it != INVALID_RSSI }
            trySend(
                WifiState.from(
                    network = network,
                    rawSsid = info?.ssid,
                    rssi = rssi,
                    level = rssi?.let { wifiManager.calculateSignalLevel(it) },
                    maxLevel = wifiManager.maxSignalLevel,
                    linkSpeedMbps = info?.linkSpeed,
                    ipv4 = v4?.address?.hostAddress,
                    prefixLength = v4?.prefixLength,
                ),
            )
        }

        val callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) {
                if (network != current) {
                    current = network
                    caps = connectivity.getNetworkCapabilities(network)
                    link = connectivity.getLinkProperties(network)
                }
                publish()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (current == null) current = network
                if (network != current) return
                caps = networkCapabilities
                publish()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                if (current == null) current = network
                if (network != current) return
                link = linkProperties
                publish()
            }

            override fun onLost(network: Network) {
                if (network != current) return
                changedAt = Clock.Monotonic.nowMs()
                current = null
                caps = null
                link = null
                publish()
            }
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivity.registerNetworkCallback(request, callback)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wi-Fi callback kaydedilemedi", e)
            trySend(WifiState.Disconnected)
        }
        awaitClose { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private companion object {
        const val TAG = "NetworkMonitor"
        const val INVALID_RSSI = -127
    }
}
