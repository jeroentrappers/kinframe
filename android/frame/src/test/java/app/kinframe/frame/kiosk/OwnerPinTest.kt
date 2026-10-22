package app.kinframe.frame.kiosk

import app.kinframe.core.api.FrameSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PinHashDto
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

private object JvmCodec : OwnerPin.Codec {
    override fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    override fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)
}

class OwnerPinTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test fun pbkdf2MatchesRfc7914Vectors() {
        // RFC 7914 §11 (the same vector the server's Go test checks).
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(OwnerPin.pbkdf2Sha256("passwd".toByteArray(), "salt".toByteArray(), 1, 64)),
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(OwnerPin.pbkdf2Sha256("Password".toByteArray(), "NaCl".toByteArray(), 80000, 64)),
        )
    }

    @Test fun hashAndVerify() {
        val h = OwnerPin.hash("4821", SecureRandom(), iterations = 1000, b64 = JvmCodec)
        assertEquals("pbkdf2-sha256", h.alg)
        assertEquals(44, h.hash.length)
        assertTrue(OwnerPin.verify(h, "4821", JvmCodec))
        assertFalse(OwnerPin.verify(h, "4820", JvmCodec))
        assertFalse(OwnerPin.verify(h, "48210", JvmCodec))
        assertFalse(OwnerPin.verify(h, "", JvmCodec))
        val again = OwnerPin.hash("4821", SecureRandom(), iterations = 1000, b64 = JvmCodec)
        assertNotEquals("salted", h.hash, again.hash)
        assertFalse("unknown algorithm", OwnerPin.verify(h.copy(alg = "md5"), "4821", JvmCodec))
        assertFalse("garbage", OwnerPin.verify(h.copy(salt = "%%%"), "4821", JvmCodec))
    }

    /** A hash made by Go's crypto/pbkdf2 (as the server does) for "4821" verifies on the frame. */
    @Test fun verifiesServerHash() {
        val h = PinHashDto("pbkdf2-sha256", 20000, "a2luZnJhbWUtc2FsdC0xNg==", "KkpJuu9AppDLk2HRW58pnLDMv+Mpu0y5pkMyYNJksr4=")
        assertTrue(OwnerPin.verify(h, "4821", JvmCodec))
        assertFalse(OwnerPin.verify(h, "1284", JvmCodec))
    }

    @Test fun validity() {
        listOf("1234", "000000", "98765").forEach { assertTrue(it, OwnerPin.isValid(it)) }
        listOf("123", "1234567", "12a4", " 1234", "١٢٣٤").forEach { assertFalse(it, OwnerPin.isValid(it)) }
    }

    @Test fun settingsParseTheHash() {
        val raw = KinJson.parseToJsonElement(
            """{"version":3,"kiosk":{"owner_pin_set":true,"owner_pin_hash":{"alg":"pbkdf2-sha256","iterations":20000,"salt":"c2FsdA==","hash":"aGFzaA=="}}}""",
        ).jsonObject
        val s = FrameSettings.parse(raw)
        assertEquals(20000, s.kiosk.ownerPinHash?.iterations)
        assertEquals("c2FsdA==", s.kiosk.ownerPinHash?.salt)
        assertNull(FrameSettings.parse(KinJson.parseToJsonElement("""{"version":1}""").jsonObject).kiosk.ownerPinHash)
    }
}

class PinAttemptsTest {
    private class MemStore : PinAttempts.Store {
        override var failures = 0
        override var lockedUntil = 0L
    }

    @Test fun fiveFreeTriesThenDoublingLockout() {
        var now = 1_000_000L
        val store = MemStore()
        val a = PinAttempts(store) { now }
        assertEquals(PinAttempts.State.Open(5), a.state())
        repeat(4) { i -> assertEquals(PinAttempts.State.Open(4 - i), a.onFailure()) }
        assertEquals(PinAttempts.State.Locked(now + 30_000), a.onFailure())
        now += 30_000
        assertEquals(PinAttempts.State.Open(0), a.state())
        assertEquals(PinAttempts.State.Locked(now + 60_000), a.onFailure())
        now += 60_000
        assertEquals(PinAttempts.State.Locked(now + 120_000), a.onFailure())
        a.onSuccess()
        assertEquals(PinAttempts.State.Open(5), a.state())
    }

    @Test fun lockoutIsCapped() {
        assertEquals(30_000L, PinAttempts.lockoutMs(5))
        assertEquals(15 * 60_000L, PinAttempts.lockoutMs(12))
        assertEquals(15 * 60_000L, PinAttempts.lockoutMs(500))
    }

    @Test fun stateSurvivesARestart() {
        val store = MemStore()
        val now = 5_000L
        repeat(5) { PinAttempts(store) { now }.onFailure() }
        assertTrue(PinAttempts(store) { now + 1_000 }.state() is PinAttempts.State.Locked)
    }
}

class OwnerGateTest {
    private class MemStore : PinAttempts.Store {
        override var failures = 0
        override var lockedUntil = 0L
    }

    private var hash: PinHashDto? = PinHashDto(salt = "x", hash = "y")
    private var elapsed = 0L
    private val gate = OwnerGate({ hash }, PinAttempts(MemStore()) { elapsed }, { elapsed }, { _, pin -> pin == "2468" })

    @Test fun noPinGrantsImmediately() {
        hash = null
        var granted = false
        gate.authorize(OwnerGate.Reason.OpenSettings) { granted = true }
        assertTrue(granted)
        assertNull(gate.request.value)
    }

    @Test fun pinRequiredThenGraceThenLock() = runBlocking {
        var granted = 0
        gate.authorize(OwnerGate.Reason.PowerOff) { granted++ }
        assertEquals(0, granted)
        assertEquals(OwnerGate.Reason.PowerOff, gate.request.value?.reason)
        assertEquals(OwnerGate.Result.Wrong(4), gate.submit("1111"))
        assertEquals(OwnerGate.Result.Granted, gate.submit("2468"))
        assertEquals(1, granted)
        assertNull(gate.request.value)
        // Within the grace period (e.g. Wi-Fi from inside settings) no second prompt.
        elapsed += 60_000
        gate.authorize(OwnerGate.Reason.Wifi) { granted++ }
        assertEquals(2, granted)
        gate.touch()
        elapsed += OwnerGate.GRACE_MS - 1
        assertTrue(gate.isUnlocked())
        gate.lock()
        gate.authorize(OwnerGate.Reason.OpenSettings) { granted++ }
        assertEquals(2, granted)
        gate.cancel()
        assertNull(gate.request.value)
    }

    @Test fun lockedOutRefusesEvenTheRightPin() = runBlocking {
        gate.authorize(OwnerGate.Reason.ExitKiosk) {}
        repeat(4) { gate.submit("0000") }
        assertTrue(gate.submit("0000") is OwnerGate.Result.Locked)
        assertTrue(gate.submit("2468") is OwnerGate.Result.Locked)
        elapsed += 30_000
        assertEquals(OwnerGate.Result.Granted, gate.submit("2468"))
    }

    @Test fun pinRemovedRemotelyWhileAsking() = runBlocking {
        var granted = false
        gate.authorize(OwnerGate.Reason.OpenSettings) { granted = true }
        hash = null
        assertEquals(OwnerGate.Result.Granted, gate.submit(""))
        assertTrue(granted)
    }
}
