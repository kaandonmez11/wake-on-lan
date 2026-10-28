package com.kdgames.wake_on_lan.net

/** Wake-on-LAN magic packet: 6 × 0xFF + MAC × 16 = 102 bayt (spec 7.2). */
object MagicPacket {
    const val SIZE = 102
    private const val REPEAT = 16

    fun build(mac: String): ByteArray {
        val macBytes = MacAddress.toBytes(mac)
        val packet = ByteArray(SIZE)
        packet.fill(0xFF.toByte(), 0, 6)
        repeat(REPEAT) { macBytes.copyInto(packet, 6 + it * macBytes.size) }
        return packet
    }
}
