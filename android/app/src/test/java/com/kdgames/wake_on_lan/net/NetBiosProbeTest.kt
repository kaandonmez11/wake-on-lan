package com.kdgames.wake_on_lan.net

import com.kdgames.wake_on_lan.net.probe.NetBiosProbe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class NetBiosProbeTest {
    /** Windows'un NBSTAT yanıtı biçiminde örnek (RFC 1002 §4.2.18): grup adı önde, MAC ve istatistikler sonda. */
    private fun response(txId: Int, mac: ByteArray = byteArrayOf(0xD8.toByte(), 0xBB.toByte(), 0xC1.toByte(), 0x0A, 0x2F, 0x91.toByte())): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(txId); u16(0x8400); u16(0); u16(1); u16(0); u16(0)
        out.write(NetBiosProbe.query(txId).copyOfRange(12, 12 + 34)) // ad: 0x20 + 32 + 0
        u16(0x21); u16(1); u16(0); u16(0)
        val names = listOf(Triple("WORKGROUP", 0x00, 0x8400), Triple("KAAN-PC", 0x00, 0x0400), Triple("KAAN-PC", 0x20, 0x0400))
        val rdata = ByteArrayOutputStream().apply {
            write(names.size)
            names.forEach { (n, suffix, flags) ->
                write(n.padEnd(15, ' ').toByteArray(Charsets.ISO_8859_1)); write(suffix); write(flags shr 8); write(flags and 0xFF)
            }
            write(mac); write(ByteArray(40))
        }.toByteArray()
        u16(rdata.size)
        out.write(rdata)
        return out.toByteArray()
    }

    @Test
    fun `sorgu RFC 1002 bicimi`() {
        val q = NetBiosProbe.query(0x1234)
        assertEquals(50, q.size)
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII))
        assertEquals(listOf(0x12, 0x34, 0, 0, 0, 1), q.take(6).map { it.toInt() and 0xFF })
        assertEquals(listOf(0, 0x21, 0, 1), q.takeLast(4).map { it.toInt() and 0xFF })
    }

    @Test
    fun `hostname grup adi atlanarak, MAC buyuk harf`() {
        val r = NetBiosProbe.parse(response(0x4242), 0x4242)!!
        assertEquals("KAAN-PC", r.hostname)
        assertEquals("D8:BB:C1:0A:2F:91", r.mac)
    }

    @Test
    fun `baska islem kimligi ya da kisa paket reddedilir`() {
        assertNull(NetBiosProbe.parse(response(0x4242), 0x1111))
        assertNull(NetBiosProbe.parse(response(0x4242).copyOf(30), 0x4242))
        assertNull(NetBiosProbe.parse(ByteArray(0), 0))
    }

    @Test
    fun `sifir MAC null`() {
        assertNull(NetBiosProbe.parse(response(7, ByteArray(6)), 7)!!.mac)
    }
}
