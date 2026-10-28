package com.kdgames.wake_on_lan.net

import com.kdgames.wake_on_lan.TestVectors
import com.kdgames.wake_on_lan.hex
import com.kdgames.wake_on_lan.str
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows

class NetPrimitivesTest {

    @Nested
    inner class MagicPacketTest {
        @Test
        fun `102 bayt, 6 x FF, MAC 16 kez`() {
            val packet = MagicPacket.build("D8:BB:C1:0A:2F:91")
            assertEquals(102, packet.size)
            assertTrue(packet.take(6).all { it == 0xFF.toByte() })
            val mac = byteArrayOf(0xD8.toByte(), 0xBB.toByte(), 0xC1.toByte(), 0x0A, 0x2F, 0x91.toByte())
            repeat(16) { assertEquals(mac.toList(), packet.drop(6 + it * 6).take(6)) }
        }

        @TestFactory
        fun `vektorlerle bayt esitligi`() = TestVectors.section("magicPackets").map { c ->
            DynamicTest.dynamicTest(c.str("mac")) {
                assertEquals(c.str("packetHex"), MagicPacket.build(c.str("mac")).hex())
            }
        }

        @Test
        fun `gecersiz MAC reddedilir`() {
            assertThrows<IllegalArgumentException> { MagicPacket.build("AA:BB") }
        }
    }

    @Nested
    inner class BroadcastResolverTest {
        @Test
        fun `spec ornekleri`() {
            assertEquals("192.168.1.255", BroadcastResolver.compute("192.168.1.24", 24))
            assertEquals("10.0.7.255", BroadcastResolver.compute("10.0.5.17", 22))
        }

        @Test
        fun `31 ve 32 icin acik hata`() {
            assertThrows<IllegalArgumentException> { BroadcastResolver.compute("192.168.1.24", 31) }
            assertThrows<IllegalArgumentException> { BroadcastResolver.compute("192.168.1.24", 32) }
        }

        @TestFactory
        fun `vektorler`() = TestVectors.section("broadcast").map { c ->
            val ip = c.str("ip")
            val prefix = c.getValue("prefix").jsonPrimitive.int
            DynamicTest.dynamicTest("$ip/$prefix") {
                if (c["error"]?.jsonPrimitive?.boolean == true) {
                    assertThrows<IllegalArgumentException> { BroadcastResolver.compute(ip, prefix) }
                } else {
                    assertEquals(c.str("broadcast"), BroadcastResolver.compute(ip, prefix))
                }
            }
        }
    }

    @Nested
    inner class MacAddressTest {
        @TestFactory
        fun `vektorler`() = TestVectors.section("macAddresses").map { c ->
            val input = c.str("input")
            DynamicTest.dynamicTest("'$input'") {
                val valid = c.getValue("valid").jsonPrimitive.boolean
                assertEquals(valid, MacAddress.isValid(input))
                if (valid) assertEquals(c.str("normalized"), MacAddress.normalize(input))
                else assertNull(MacAddress.normalize(input))
            }
        }

        @Test
        fun `kucuk harf ve tire normalize edilir`() {
            assertEquals("AA:BB:CC:DD:EE:FF", MacAddress.normalize("aa-bb-cc-dd-ee-ff"))
            assertFalse(MacAddress.isValid("aabbccddeeff"))
        }
    }
}
