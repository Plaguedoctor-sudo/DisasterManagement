package network.disaster.management

import network.disaster.management.crypto.CredentialSecurityPolicy
import network.disaster.management.crypto.FipsCryptographicSelfTest
import network.disaster.management.crypto.NativeMemoryLock
import network.disaster.management.transport.MeshRateLimiter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBlueprintProtectiveMeasuresTest {

    @Test
    fun testFips140PowerOnSelfTestPasses() {
        val result = FipsCryptographicSelfTest.runPowerOnSelfTest()
        assertTrue("FIPS 140-3 power-on self-test KAT vectors must pass", result)
    }

    @Test
    fun testCredentialSecurityPolicyAntiSpray() {
        // Weak / short credentials must fail
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("short").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("allalphabetsonly").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("123456789").isValid)

        // Banned default passwords must fail
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("admin123").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("password123").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("doctor123").isValid)

        // Seasonal templates must fail
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("Spring2026!").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("Summer2024").isValid)
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("Fall2019!").isValid)

        // Repetitive patterns must fail
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("aaaaaaaa").isValid)

        // Compliant high-entropy credentials must pass
        assertTrue(CredentialSecurityPolicy.validateCredentialPolicy("TacticalCivil#2026").isValid)
        assertTrue(CredentialSecurityPolicy.validateCredentialPolicy("DisasterMesh42!Rescue").isValid)
    }

    @Test
    fun testDigitalSealConstantTimeVerification() {
        val payload = "TRIAGE_RECORD:PATIENT_101:STABLE".toByteArray(Charsets.UTF_8)
        val secretKey = ByteArray(32) { (it + 5).toByte() }

        val sealHex = CredentialSecurityPolicy.calculateDigitalSeal(payload, secretKey)
        assertTrue("Seal must verify with correct key and payload",
            CredentialSecurityPolicy.verifyDigitalSeal(payload, secretKey, sealHex))

        val tamperedPayload = "TRIAGE_RECORD:PATIENT_101:CRITICAL".toByteArray(Charsets.UTF_8)
        assertFalse("Tampered payload must fail seal verification",
            CredentialSecurityPolicy.verifyDigitalSeal(tamperedPayload, secretKey, sealHex))

        val wrongKey = ByteArray(32) { (it + 9).toByte() }
        assertFalse("Wrong key must fail seal verification",
            CredentialSecurityPolicy.verifyDigitalSeal(payload, wrongKey, sealHex))
    }

    @Test
    fun testMeshRateLimiterDualKeyProtection() {
        val limiter = MeshRateLimiter(peerLimitPerSec = 5, channelLimitPerSec = 10)
        val peerA = "192.168.43.10"
        val peerB = "192.168.43.11"

        // First 5 packets from peerA within 1 sec should be allowed
        for (i in 1..5) {
            assertTrue("Packet $i should be permitted", limiter.allowPacket(peerA, "GROUP_CHAT"))
        }

        // 6th packet from peerA within 1 sec should be blocked by peer rate limit
        assertFalse("Packet 6 from peerA should be throttled", limiter.allowPacket(peerA, "GROUP_CHAT"))

        // PeerB should still be allowed since rate limiter is dual-keyed per peer
        assertTrue("PeerB should be allowed under independent peer key", limiter.allowPacket(peerB, "GROUP_CHAT"))
    }

    @Test
    fun testNativeMemoryLockSecureWipeDoD() {
        val lock = NativeMemoryLock()
        val sensitiveBuffer = byteArrayOf(0x41, 0x42, 0x43, 0x44, 0x45, 0x46)
        lock.secureWipe(sensitiveBuffer)

        // After DoD 5220.22-M 3-pass wipe, buffer must be completely zeroized
        for (b in sensitiveBuffer) {
            assertTrue("Wiped buffer byte must be 0x00", b == 0.toByte())
        }
    }
}
