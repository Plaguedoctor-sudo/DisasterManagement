package network.disaster.management.security

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Active Deception, Honeypots & Automated Network Quarantine (Blueprint Section 9).
 * Detects malicious reconnaissance attempts hitting canary trap paths, engages active deception,
 * and maintains an automated 24-hour quarantine isolating hostile actors from both HTTP and mesh ingress.
 */
object NetworkQuarantineManager {

    private const val TAG = "QuarantineManager"
    const val DEFAULT_QUARANTINE_MS = 24 * 60 * 60 * 1000L // 24 Hours

    /**
     * Standard Reconnaissance Canary Trap Paths (Blueprint Section 9.1).
     */
    val CANARY_TRAP_PATHS = setOf(
        "/.env",
        "/.git",
        "/.git/config",
        "/wp-admin",
        "/wp-login.php",
        "/phpmyadmin",
        "/.aws",
        "/.aws/credentials",
        "/config.json",
        "/shell.php",
        "/admin.php",
        "/actuator/health",
        "/id_rsa",
        "/.ssh/id_rsa"
    )

    data class QuarantineRecord(
        val ipAddress: String,
        val reason: String,
        val quarantinedAt: Long,
        val expiresAt: Long
    )

    private val quarantinedIps = ConcurrentHashMap<String, QuarantineRecord>()

    /**
     * Inspects a requested path to determine if it strikes a honeypot canary trap.
     */
    fun isCanaryPath(path: String): Boolean {
        val cleanPath = path.lowercase().split("?").firstOrNull()?.trim() ?: ""
        return CANARY_TRAP_PATHS.any { cleanPath == it || cleanPath.startsWith("$it/") }
    }

    /**
     * Imposes an automated 24-hour quarantine on the specified IP address.
     */
    fun quarantineIp(ip: String, reason: String, durationMs: Long = DEFAULT_QUARANTINE_MS): QuarantineRecord {
        val now = System.currentTimeMillis()
        val record = QuarantineRecord(
            ipAddress = ip,
            reason = reason,
            quarantinedAt = now,
            expiresAt = now + durationMs
        )
        quarantinedIps[ip] = record
        try {
            Log.w(TAG, "🚨 SECURITY QUARANTINE ENGAGED: $ip isolated for ${durationMs / 3600000}h. Reason: $reason")
        } catch (_: Throwable) {}
        return record
    }

    /**
     * Checks if an IP is currently under active quarantine.
     */
    fun isQuarantined(ip: String): Boolean {
        val cleanIp = ip.replace("^/+".toRegex(), "").replace("::ffff:", "").trim()
        val record = quarantinedIps[cleanIp] ?: return false
        val now = System.currentTimeMillis()
        if (now > record.expiresAt) {
            quarantinedIps.remove(cleanIp)
            return false
        }
        return true
    }

    /**
     * Returns all currently active quarantine records.
     */
    fun getActiveQuarantines(): List<QuarantineRecord> {
        val now = System.currentTimeMillis()
        // Clean expired
        quarantinedIps.entries.removeIf { it.value.expiresAt <= now }
        return quarantinedIps.values.toList()
    }

    /**
     * Releases an IP from quarantine.
     */
    fun releaseIp(ip: String) {
        val cleanIp = ip.replace("^/+".toRegex(), "").replace("::ffff:", "").trim()
        quarantinedIps.remove(cleanIp)
    }

    /**
     * Clears all quarantine entries (e.g. for testing or administrative reset).
     */
    fun clearAll() {
        quarantinedIps.clear()
    }
}
