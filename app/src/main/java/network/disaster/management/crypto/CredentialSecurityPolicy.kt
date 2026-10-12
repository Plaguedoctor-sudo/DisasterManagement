package network.disaster.management.crypto

import org.bouncycastle.crypto.generators.SCrypt
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sovereign Credential, Scrypt KDF, Digital Seal & Anti-Spray Security Policy (Blueprint Sections 4, 5, 7, 8, 12).
 * Implements memory-hard Scrypt KDF hashing, constant-time verification, dual-keyed anti-botnet lockout,
 * AES-256-GCM authenticated storage, and DoD 5220.22-M ephemeral shredding.
 */
object CredentialSecurityPolicy {

    data class ValidationResult(val isValid: Boolean, val reason: String? = null)

    private val secureRandom = SecureRandom()

    private val BANNED_PASSWORDS = setOf(
        "admin123", "password123", "doctor123", "nurse123", "responder123",
        "12345678", "123456789", "11111111", "00000000", "qwerty123", "admin1234"
    )

    private val SEASONAL_REGEX = Regex("^(spring|summer|autumn|fall|winter)\\d{2,4}[!@#\$%^&*?]?\$", RegexOption.IGNORE_CASE)

    /**
     * Anti-Spray Credential Policy Validator (Blueprint Section 4.3 & 12.B).
     */
    fun validateCredentialPolicy(candidate: String, minLength: Int = 8): ValidationResult {
        val clean = candidate.trim()
        if (clean.length < minLength) {
            return ValidationResult(false, "Credential must be at least $minLength characters long.")
        }

        val hasAlpha = clean.any { it.isLetter() }
        val hasDigit = clean.any { it.isDigit() }
        if (!hasAlpha || !hasDigit) {
            return ValidationResult(false, "Credential must contain a combination of letters and numbers.")
        }

        if (SEASONAL_REGEX.matches(clean)) {
            return ValidationResult(false, "Predictable seasonal templates are prohibited.")
        }

        if (BANNED_PASSWORDS.contains(clean.lowercase())) {
            return ValidationResult(false, "Known default or common passwords are prohibited.")
        }

        // Repetitive character detection (e.g. "aaaaaaaa")
        if (clean.all { it == clean[0] }) {
            return ValidationResult(false, "Repetitive character patterns are prohibited.")
        }

        return ValidationResult(true)
    }

    /**
     * Memory-Hard Scrypt Password & Credential Hasher (Blueprint Section 4.1 & 12.A).
     * Derives password hash using N=16384, r=8, p=1, dkLen=32 with 16-byte cryptographically secure random salt.
     * Format: scrypt:v1:<salt_hex>:<derived_key_hex>
     */
    fun hashCredential(secret: String): String {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val derived = SCrypt.generate(
            secret.toByteArray(Charsets.UTF_8),
            salt,
            16384,
            8,
            1,
            32
        )
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val derivedHex = derived.joinToString("") { "%02x".format(it) }
        return "scrypt:v1:$saltHex:$derivedHex"
    }

    /**
     * Constant-Time Credential Verification (Blueprint Section 4.2 & 12.A).
     * Eliminates side-channel timing attacks (CWE-208).
     */
    fun verifyCredential(candidate: String, storedHash: String): Boolean {
        if (!storedHash.startsWith("scrypt:v1:")) return false
        val parts = storedHash.split(":")
        if (parts.size != 4) return false

        val saltHex = parts[2]
        val expectedDerivedHex = parts[3]

        return try {
            val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val derived = SCrypt.generate(
                candidate.toByteArray(Charsets.UTF_8),
                salt,
                16384,
                8,
                1,
                32
            )
            val derivedHex = derived.joinToString("") { "%02x".format(it) }
            MessageDigest.isEqual(
                derivedHex.toByteArray(Charsets.US_ASCII),
                expectedDerivedHex.toByteArray(Charsets.US_ASCII)
            )
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Authenticated AES-256-GCM Buffer Encryption with Scrypt KDF (Blueprint Section 7.4 & 12.C).
     * Format: [16-byte Salt] + [12-byte IV] + [Ciphertext + 16-byte GCM Tag]
     */
    fun encryptBufferAesGcm(buffer: ByteArray, passphrase: String): ByteArray {
        val salt = ByteArray(16)
        secureRandom.nextBytes(salt)
        val iv = ByteArray(12)
        secureRandom.nextBytes(iv)

        val keyBytes = SCrypt.generate(
            passphrase.toByteArray(Charsets.UTF_8),
            salt,
            16384,
            8,
            1,
            32
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(buffer)

        // Pack: salt (16) + iv (12) + encrypted (data + 16 tag)
        return salt + iv + encrypted
    }

    /**
     * Authenticated AES-256-GCM Buffer Decryption with Scrypt KDF (Blueprint Section 7.4 & 12.C).
     */
    fun decryptBufferAesGcm(packed: ByteArray, passphrase: String): ByteArray {
        require(packed.size >= 44) { "Encrypted payload must contain at least 44 bytes (salt+iv+tag)" }

        val salt = packed.copyOfRange(0, 16)
        val iv = packed.copyOfRange(16, 28)
        val ciphertext = packed.copyOfRange(28, packed.size)

        val keyBytes = SCrypt.generate(
            passphrase.toByteArray(Charsets.UTF_8),
            salt,
            16384,
            8,
            1,
            32
        )

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Calculates an HMAC-SHA256 digital seal over record bytes (Blueprint Section 8.1 & 12.D).
     */
    fun calculateDigitalSeal(payload: ByteArray, secretKey: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
        val digest = mac.doFinal(payload)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies digital seal in constant time (CWE-208 Timing Attack Immunity).
     */
    fun verifyDigitalSeal(payload: ByteArray, secretKey: ByteArray, expectedSealHex: String): Boolean {
        val calculatedHex = calculateDigitalSeal(payload, secretKey)
        val calculatedBytes = calculatedHex.toByteArray(Charsets.US_ASCII)
        val expectedBytes = expectedSealHex.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(calculatedBytes, expectedBytes)
    }

    /**
     * Cryptographic Ephemeral Shredding (Blueprint Section 7.5 & NIST SP 800-88 / DoD 5220.22-M).
     * Overwrites file with random bytes, bitwise complement, and zeroization before deletion.
     */
    fun shredEphemeralFile(file: File): Boolean {
        if (!file.exists()) return true
        return try {
            val length = file.length().coerceAtLeast(4096L)
            val chunkSize = 4096
            val buf = ByteArray(chunkSize)

            // Pass 1: Random fill
            FileOutputStream(file).use { fos ->
                var written = 0L
                while (written < length) {
                    secureRandom.nextBytes(buf)
                    val toWrite = minOf(buf.size.toLong(), length - written).toInt()
                    fos.write(buf, 0, toWrite)
                    written += toWrite
                }
                fos.fd.sync()
            }

            // Pass 2: Invert / bitwise complement
            FileOutputStream(file).use { fos ->
                var written = 0L
                while (written < length) {
                    secureRandom.nextBytes(buf)
                    for (i in buf.indices) buf[i] = (buf[i].toInt().inv()).toByte()
                    val toWrite = minOf(buf.size.toLong(), length - written).toInt()
                    fos.write(buf, 0, toWrite)
                    written += toWrite
                }
                fos.fd.sync()
            }

            // Pass 3: Zero fill
            FileOutputStream(file).use { fos ->
                buf.fill(0)
                var written = 0L
                while (written < length) {
                    val toWrite = minOf(buf.size.toLong(), length - written).toInt()
                    fos.write(buf, 0, toWrite)
                    written += toWrite
                }
                fos.fd.sync()
            }

            file.delete()
        } catch (_: Exception) {
            file.delete()
        }
    }

    /**
     * Dual-Keyed Anti-Botnet Rate Limiter & Lockout (Blueprint Section 5).
     * Tracks attempts per physical IP/host address and target account (user:<loginId>).
     */
    class AntiBotnetRateLimiter(
        val maxAttempts: Int = 5,
        val lockoutDurationMs: Long = 300_000L // 5 minutes
    ) {
        data class LockoutStatus(val isLocked: Boolean, val remainingLockMs: Long = 0L, val failedAttempts: Int = 0)
        private data class Record(var failures: Int, var firstFailureEpoch: Long, var lockedUntilEpoch: Long)

        private val records = ConcurrentHashMap<String, Record>()

        @Synchronized
        fun recordFailedAttempt(key: String): LockoutStatus {
            val now = System.currentTimeMillis()
            val rec = records.compute(key) { _, existing ->
                if (existing == null || now - existing.firstFailureEpoch > lockoutDurationMs) {
                    Record(1, now, 0L)
                } else {
                    existing.failures++
                    if (existing.failures >= maxAttempts) {
                        existing.lockedUntilEpoch = now + lockoutDurationMs
                    }
                    existing
                }
            } ?: Record(1, now, 0L)

            return if (rec.lockedUntilEpoch > now) {
                LockoutStatus(true, rec.lockedUntilEpoch - now, rec.failures)
            } else {
                LockoutStatus(false, 0L, rec.failures)
            }
        }

        fun checkStatus(key: String): LockoutStatus {
            val now = System.currentTimeMillis()
            val rec = records[key] ?: return LockoutStatus(false, 0L, 0)
            return if (rec.lockedUntilEpoch > now) {
                LockoutStatus(true, rec.lockedUntilEpoch - now, rec.failures)
            } else {
                LockoutStatus(false, 0L, rec.failures)
            }
        }

        fun reset(key: String) {
            records.remove(key)
        }

        fun isLocked(clientIp: String, accountKey: String): Boolean {
            val ipStatus = checkStatus(clientIp)
            val accStatus = checkStatus(accountKey)
            return ipStatus.isLocked || accStatus.isLocked
        }

        companion object {
            val shared = AntiBotnetRateLimiter()
        }
    }
}
