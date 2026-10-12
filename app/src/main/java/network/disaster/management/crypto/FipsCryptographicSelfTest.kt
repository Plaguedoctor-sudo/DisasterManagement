package network.disaster.management.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * FIPS 140-3 & NIST SP 800-140D Power-On Known Answer Self-Tests (KAT) (Blueprint Section 14.A).
 * Validates cryptographic primitives (SHA-256, HMAC-SHA256, AES-256-GCM, Scrypt KDF, and CRNG)
 * against authoritative standard test vectors on system startup.
 */
object FipsCryptographicSelfTest {

    class CryptographicContainmentException(message: String) : SecurityException(message)

    @Synchronized
    fun runPowerOnSelfTest(): Boolean {
        // 1. SHA-256 Known Answer Test (FIPS 180-4)
        if (!verifySha256Kat()) {
            throw CryptographicContainmentException("FIPS 140-3 KAT FAILURE: SHA-256 test vector mismatch")
        }

        // 2. HMAC-SHA256 Known Answer Test (RFC 4231 Test Case 2)
        if (!verifyHmacSha256Kat()) {
            throw CryptographicContainmentException("FIPS 140-3 KAT FAILURE: HMAC-SHA256 test vector mismatch")
        }

        // 3. AES-256-GCM Known Answer Test (NIST SP 800-38D)
        if (!verifyAes256GcmKat()) {
            throw CryptographicContainmentException("FIPS 140-3 KAT FAILURE: AES-256-GCM test vector mismatch")
        }

        // 4. Scrypt KDF Known Answer Test (RFC 7914 Test Case 3)
        if (!verifyScryptKdfKat()) {
            throw CryptographicContainmentException("FIPS 140-3 KAT FAILURE: Scrypt KDF test vector mismatch")
        }

        // 5. Continuous Random Number Generator (CRNG) Health Test (NIST SP 800-90B)
        if (!verifyCrngHealth()) {
            throw CryptographicContainmentException("FIPS 140-3 CRNG FAILURE: Entropy degradation / stuck-bit detected")
        }

        return true
    }

    private fun verifySha256Kat(): Boolean {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val input = "abc".toByteArray(Charsets.US_ASCII)
            val digest = md.digest(input)
            val hex = digest.joinToString("") { "%02x".format(it) }
            hex.equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyHmacSha256Kat(): Boolean {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            val key = SecretKeySpec("Jefe".toByteArray(Charsets.US_ASCII), "HmacSHA256")
            mac.init(key)
            val input = "what do ya want for nothing?".toByteArray(Charsets.US_ASCII)
            val result = mac.doFinal(input)
            val hex = result.joinToString("") { "%02x".format(it) }
            hex.equals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyAes256GcmKat(): Boolean {
        return try {
            val keyBytes = ByteArray(32) { 0x00 }
            val ivBytes = ByteArray(12) { 0x00 }
            val plaintext = ByteArray(0)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, ivBytes))
            val ciphertextAndTag = cipher.doFinal(plaintext)

            // For empty plaintext, ciphertextAndTag contains exactly the 16-byte authentication tag
            val tagHex = ciphertextAndTag.joinToString("") { "%02x".format(it) }
            tagHex.equals("530f8afbc74536b9a963b4f1c4cb738b", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyScryptKdfKat(): Boolean {
        return try {
            // RFC 7914 Section 3 Test Case 3:
            // P = "pleaseletmein", S = "SodiumChloride", N = 16384, r = 8, p = 1, dkLen = 64
            val password = "pleaseletmein".toByteArray(Charsets.UTF_8)
            val salt = "SodiumChloride".toByteArray(Charsets.UTF_8)
            val derived = SCrypt.generate(password, salt, 16384, 8, 1, 64)
            val hex = derived.joinToString("") { "%02x".format(it) }
            hex.equals(
                "7023bdcb3afd7348461c06cd81fd38ebfda8fbba904f8e3ea9b543f6545da1f2d5432955613f0fcf62d49705242a9af9e61e85dc0d651e40dfcf017b45575887",
                ignoreCase = true
            )
        } catch (_: Throwable) {
            false
        }
    }

    private fun verifyCrngHealth(): Boolean {
        return try {
            val rng = SecureRandom()
            val sample1 = ByteArray(32)
            val sample2 = ByteArray(32)
            rng.nextBytes(sample1)
            rng.nextBytes(sample2)

            // Stuck-bit & repetition check
            if (sample1.all { it == 0.toByte() } || sample2.all { it == 0.toByte() }) return false
            if (sample1.all { it == sample1[0] }) return false
            if (sample1.contentEquals(sample2)) return false

            true
        } catch (_: Throwable) {
            false
        }
    }
}
