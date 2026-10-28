package com.kdgames.wake_on_lan.data.crypto

import com.kdgames.wake_on_lan.TestVectors
import com.kdgames.wake_on_lan.b64
import com.kdgames.wake_on_lan.hex
import com.kdgames.wake_on_lan.hexBytes
import com.kdgames.wake_on_lan.net.agent.AgentCrypto
import com.kdgames.wake_on_lan.str
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom

class CryptoVectorTest {

    @Test
    fun `X25519 RFC 7748 bilinen sonuc`() {
        // vectors.json'dan bağımsız sabit.
        val alicePriv = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a".hexBytes()
        val bobPub = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f".hexBytes()
        assertEquals("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", X25519.publicKey(alicePriv).hex())
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            X25519.sharedSecret(alicePriv, bobPub).hex(),
        )
    }

    @TestFactory
    fun `X25519 vektorleri`() = TestVectors.section("x25519").map { c ->
        DynamicTest.dynamicTest(c.str("name")) {
            assertArrayEquals(c.b64("alicePub"), X25519.publicKey(c.b64("alicePriv")))
            assertArrayEquals(c.b64("bobPub"), X25519.publicKey(c.b64("bobPriv")))
            assertArrayEquals(c.b64("shared"), X25519.sharedSecret(c.b64("alicePriv"), c.b64("bobPub")))
            assertArrayEquals(c.b64("shared"), X25519.sharedSecret(c.b64("bobPriv"), c.b64("alicePub")))
        }
    }

    @TestFactory
    fun `HKDF vektorleri`() = TestVectors.section("hkdf").map { c ->
        DynamicTest.dynamicTest(c.str("name")) {
            val okm = Hkdf.derive(c.b64("ikm"), c.b64("salt"), c.b64("info"), c.getValue("length").jsonPrimitive.int)
            assertArrayEquals(c.b64("okm"), okm)
        }
    }

    @Test
    fun `eslesme iki tarafta ayni oturum anahtarini uretir`() {
        val p = TestVectors.obj("pairing")
        val agentId = p.str("agentId")
        val phoneId = p.str("phoneId")
        assertEquals(agentId + phoneId, p.str("salt"))
        val phoneSide = X25519.sharedSecret(p.b64("phonePriv"), p.b64("agentPub"))
        val agentSide = X25519.sharedSecret(p.b64("agentPriv"), p.b64("phonePub"))
        assertArrayEquals(p.b64("sharedSecret"), phoneSide)
        assertArrayEquals(phoneSide, agentSide)
        assertArrayEquals(TestVectors.sessionKey, AgentCrypto.deriveSessionKey(phoneSide, agentId, phoneId))
    }

    @Test
    fun `rastgele anahtar ciftleri uzlasir`() {
        val random = SecureRandom()
        val a = X25519.generateKeyPair(random)
        val b = X25519.generateKeyPair(random)
        assertArrayEquals(
            X25519.sharedSecret(a.privateKey, b.publicKey),
            X25519.sharedSecret(b.privateKey, a.publicKey),
        )
    }

    @Test
    fun `yanlis uzunluktaki anahtar reddedilir`() {
        assertThrows<IllegalArgumentException> { X25519.publicKey(ByteArray(31)) }
        assertThrows<IllegalArgumentException> { X25519.sharedSecret(ByteArray(32), ByteArray(33)) }
    }

    @Test
    fun `HKDF gecersiz uzunluk`() {
        assertThrows<IllegalArgumentException> { Hkdf.expand(ByteArray(32), ByteArray(0), 0) }
        assertThrows<IllegalArgumentException> { Hkdf.expand(ByteArray(32), ByteArray(0), 255 * 32 + 1) }
    }
}
