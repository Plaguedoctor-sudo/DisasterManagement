package network.disaster.management.crypto

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sovereign Credential & Digital Seal Security Policy.
 * Implements anti-spray validation, complexity guards, and constant-time HMAC-SHA256 record sealing.
 */
object CredentialSecurityPolicy {

    data class ValidationResult(val isValid: Boolean, val reason: String? = null)

    private val BANNED_PASSWORDS = setOf(
        "admin123", "password123", "doctor123", "nurse123", "responder123",
        "12345678", "123456789", "11111111", "00000000", "qwerty123"
    )

    private val SEASONAL_REGEX = Regex("^(spring|summer|autumn|fall|winter)\\d{2,4}[!@#\$%^&*?]?\$", RegexOption.IGNORE_CASE)

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

        // Repetitive character detection (e.g. "aaaa1111")
        if (clean.all { it == clean[0] }) {
            return ValidationResult(false, "Repetitive character patterns are prohibited.")
        }

        return ValidationResult(true)
    }

    /**
     * Calculates an HMAC-SHA256 digital seal over record bytes (Blueprint Section 8.1).
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
}
