package com.kdgames.wake_on_lan.net

import java.io.IOException
import java.net.DatagramSocket
import java.net.Socket
import java.net.SocketAddress
import javax.inject.Inject
import javax.inject.Singleton

/** Wi-Fi bağlı değilken soket istenirse. Mobil veriye asla düşülmez (spec 7.1). */
class NoWifiException : IOException("Wi-Fi yok")

/**
 * Uygulamadaki tüm UDP/TCP soketlerinin tek kaynağı (WoL, probe, agent, tarama).
 * Döndürülen soket Wi-Fi ağına bağlanmış (bindSocket) ve henüz bağlantısızdır.
 */
interface SocketFactory {
    /** @throws NoWifiException */
    fun udp(): DatagramSocket

    /** @throws NoWifiException */
    fun tcp(): Socket
}

@Singleton
class WifiSocketFactory @Inject constructor(
    private val monitor: NetworkMonitor,
) : SocketFactory {

    override fun udp(): DatagramSocket {
        val network = monitor.state.value.network ?: throw NoWifiException()
        val socket = DatagramSocket(null as SocketAddress?)
        try {
            // bindSocket bağlanmamış soketi önce wildcard adrese bağlar, sonra ağa sabitler.
            network.bindSocket(socket)
        } catch (_: IOException) {
            // Ağ kaybolmuş ya da değişmiş (Wi-Fi kapandı): mobil veriye düşülmez.
            socket.close()
            throw NoWifiException()
        }
        return socket
    }

    override fun tcp(): Socket {
        val network = monitor.state.value.network ?: throw NoWifiException()
        val socket = Socket()
        try {
            network.bindSocket(socket)
        } catch (_: IOException) {
            // Ağ kaybolmuş ya da değişmiş (Wi-Fi kapandı): mobil veriye düşülmez.
            socket.close()
            throw NoWifiException()
        }
        return socket
    }
}
