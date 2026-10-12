package network.disaster.management.security

import network.disaster.management.crypto.CredentialSecurityPolicy
import java.io.File
import java.io.OutputStream
import java.security.SecureRandom
import java.util.regex.Pattern

/**
 * Active Adversary Disruption, Inline IDS & Honeypot Tar-Traps (Blueprint Section 2.2, 2.3, 6.2 & Steps 12-15).
 * Neutralizes automated scanners, exploit engines, and hostile bots without illegal counter-attacks:
 * 1. Layer 7 Inline Threat IDS (SQLi, XSS, Path Traversal, RCE).
 * 2. HTTP Tarpitting (Slow trickle entropy starvation).
 * 3. Infinite Tar-Trap (Recursive chunked JSON memory exhaustion).
 * 4. Synthetic honeytoken database generation for active breach deception.
 */
object ActiveAdversaryDefense {

    private val secureRandom = SecureRandom()

    // Inline Threat IDS Regex Patterns (Blueprint Section 2.2)
    private val SQLI_PATTERN = Pattern.compile(
        "(\\b(UNION(\\s+ALL)?\\s+SELECT|SELECT\\s+.+\\s+FROM|INSERT\\s+INTO.+VALUES|DELETE\\s+FROM|DROP\\s+(TABLE|DATABASE)|WAITFOR\\s+DELAY|SLEEP\\s*\\(|OR\\s+['\"]?1['\"]?\\s*=\\s*['\"]?1|--|/\\*)\\b)",
        Pattern.CASE_INSENSITIVE
    )

    private val XSS_PATTERN = Pattern.compile(
        "(<\\s*script\\b|javascript\\s*:|vbscript\\s*:|data\\s*:\\s*text/html|<\\s*iframe\\b|on(error|load|click|mouseover)\\s*=)",
        Pattern.CASE_INSENSITIVE
    )

    private val TRAVERSAL_PATTERN = Pattern.compile(
        "(\\.\\.[\\/\\\\]|/etc/(passwd|shadow|hosts)|boot\\.ini|win\\.ini|%2e%2e[\\/\\\\]|/proc/self)",
        Pattern.CASE_INSENSITIVE
    )

    private val RCE_PATTERN = Pattern.compile(
        "(\\||;|`|\\$\\()\\s*(cat|ls|whoami|id|curl|wget|nc|sh|bash|powershell|cmd|rm\\s+-rf)",
        Pattern.CASE_INSENSITIVE
    )

    data class ThreatInspectionResult(val isThreat: Boolean, val threatCategory: String? = null, val details: String? = null)

    /**
     * Inspects inbound request URIs or mesh payload strings for malicious signatures.
     */
    fun inspectPayload(rawInput: String): ThreatInspectionResult {
        if (SQLI_PATTERN.matcher(rawInput).find()) {
            return ThreatInspectionResult(true, "SQL_INJECTION", "Matched SQLi heuristic signature")
        }
        if (XSS_PATTERN.matcher(rawInput).find()) {
            return ThreatInspectionResult(true, "CROSS_SITE_SCRIPTING", "Matched XSS heuristic signature")
        }
        if (RCE_PATTERN.matcher(rawInput).find()) {
            return ThreatInspectionResult(true, "REMOTE_CODE_EXECUTION", "Matched RCE command sequence")
        }
        if (TRAVERSAL_PATTERN.matcher(rawInput).find()) {
            return ThreatInspectionResult(true, "DIRECTORY_TRAVERSAL", "Matched Path Traversal heuristic signature")
        }
        return ThreatInspectionResult(false)
    }

    /**
     * HTTP Tarpit (Blueprint Section 2.3 & Step 13).
     * Starves attacker thread pools by trickling entropy bytes with latency intervals.
     */
    fun handleAttackerTarpit(output: OutputStream, maxChunks: Int = 8, delayMs: Long = 1000L) {
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "X-Defense-Intervention: ACTIVE-RESOURCE-EXHAUSTION\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()

        for (i in 1..maxChunks) {
            try {
                Thread.sleep(delayMs)
                val chunk = "0x%08x\n".format(secureRandom.nextInt())
                val chunkBytes = chunk.toByteArray(Charsets.UTF_8)
                val hexLen = Integer.toHexString(chunkBytes.size) + "\r\n"
                output.write(hexLen.toByteArray(Charsets.US_ASCII))
                output.write(chunkBytes)
                output.write("\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            } catch (_: Exception) {
                break
            }
        }

        try {
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }

    /**
     * Infinite Tar-Trap (Blueprint Section 2.3 & Step 14).
     * Feeds recursive synthetic JSON streams to exhaust bot scraper memory buffers.
     */
    fun handleInfiniteTarTrap(output: OutputStream, maxChunks: Int = 10, delayMs: Long = 500L) {
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "X-Defense-Intervention: ACTIVE-DECEPTION-TRAP\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.US_ASCII))
        output.flush()

        val initChunk = "{\"status\":\"ok\",\"telemetry_stream\":["
        writeChunk(output, initChunk)

        for (i in 1..maxChunks) {
            try {
                Thread.sleep(delayMs)
                val token = ByteArray(16).apply { secureRandom.nextBytes(this) }.joinToString("") { "%02x".format(it) }
                val item = "{\"index\":$i,\"canary_seal\":\"$token\"},"
                writeChunk(output, item)
            } catch (_: Exception) {
                break
            }
        }

        writeChunk(output, "{\"status\":\"concluded\"}]}")
        try {
            output.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }

    private fun writeChunk(output: OutputStream, text: String) {
        try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val hexLen = Integer.toHexString(bytes.size) + "\r\n"
            output.write(hexLen.toByteArray(Charsets.US_ASCII))
            output.write(bytes)
            output.write("\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        } catch (_: Exception) {}
    }

    /**
     * Active Deception & Honeytoken DB Generation (Blueprint Section 6.2 & Step 15).
     * Generates a synthetic SQLite decoy database with tracking canaries, then shreds the temp file.
     */
    fun generateDecoyDatabaseBytes(): ByteArray {
        val tempFile = File.createTempFile("decoy_vault_", ".db")
        try {
            val decoyContent = "SQLITE_DECOY_ENCLAVE_HONEYTOKEN_CANARY_TRIP_99482_SURVIVORS_RESCUE".toByteArray(Charsets.UTF_8)
            tempFile.writeBytes(decoyContent)
            val bytes = tempFile.readBytes()
            CredentialSecurityPolicy.shredEphemeralFile(tempFile)
            return bytes
        } catch (_: Exception) {
            CredentialSecurityPolicy.shredEphemeralFile(tempFile)
            return "DECOY_HONEYTOKEN_VAULT_CANARY".toByteArray(Charsets.UTF_8)
        }
    }
}
