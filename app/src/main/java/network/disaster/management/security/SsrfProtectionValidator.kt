package network.disaster.management.security

import java.net.URI

/**
 * Strict SSRF (Server-Side Request Forgery) Protection Engine (Blueprint Section 7.1 & Step 11).
 * Validates outbound endpoints, P2P mesh relay links, and external sync targets.
 * Filters and blocks:
 * 1. Cloud instance metadata endpoints (169.254.169.254, metadata.google.internal).
 * 2. Unintended loopback endpoints (127.0.0.0/8, ::1, localhost).
 * 3. Link-local and unspecified addresses (fe80::, 0.0.0.0).
 */
object SsrfProtectionValidator {

    data class ValidationResult(val isValid: Boolean, val errorReason: String? = null)

    private val BLOCKED_HOSTNAMES = setOf(
        "169.254.169.254",          // AWS, GCP, Azure, OpenStack instance metadata
        "metadata.google.internal", // GCP internal metadata
        "metadata",                 // Generic cloud metadata alias
        "instance-data",            // OpenStack metadata alias
        "0.0.0.0",
        "127.0.0.1",
        "localhost",
        "::1",
        "[::1]"
    )

    fun validateEndpointUrl(urlString: String, allowLoopback: Boolean = false): ValidationResult {
        return try {
            val uri = URI(urlString.trim())
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return ValidationResult(false, "Prohibited protocol scheme '$scheme'. Only HTTP and HTTPS are permitted.")
            }

            val host = uri.host?.lowercase()?.trim()
                ?: return ValidationResult(false, "Malformed endpoint URL: Missing host component.")

            if (!allowLoopback && (host == "localhost" || host == "127.0.0.1" || host.startsWith("127.") || host == "::1" || host == "[::1]")) {
                return ValidationResult(false, "SSRF VIOLATION: Loopback address '$host' is strictly prohibited.")
            }

            if (BLOCKED_HOSTNAMES.contains(host)) {
                return ValidationResult(false, "SSRF VIOLATION: Prohibited cloud metadata or reserved host '$host'.")
            }

            if (host.startsWith("fe80:") || host.startsWith("169.254.")) {
                return ValidationResult(false, "SSRF VIOLATION: Link-local / cloud metadata address '$host' is prohibited.")
            }

            ValidationResult(true)
        } catch (_: Exception) {
            ValidationResult(false, "Malformed endpoint URL format.")
        }
    }
}
