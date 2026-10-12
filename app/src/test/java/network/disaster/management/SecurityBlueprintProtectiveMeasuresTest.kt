package network.disaster.management

import network.disaster.management.crypto.CredentialSecurityPolicy
import network.disaster.management.crypto.FipsCryptographicSelfTest
import network.disaster.management.crypto.NativeMemoryLock
import network.disaster.management.security.CitadelDefenseManager
import network.disaster.management.security.MultiLevelSecurityPolicy
import network.disaster.management.security.NetworkQuarantineManager
import network.disaster.management.security.TwoPersonIntegrityManager
import network.disaster.management.transport.ApkPropagationServer
import network.disaster.management.transport.MeshRateLimiter
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.SecureRandom

/**
 * Sovereign High-Assurance Security Blueprint & Protective Measures Verification Suite.
 * Validates compliance with:
 * NIST SSDF (SP 800-218) • NIST SP 800-88 Rev. 1 • DoD STIG High Baseline • FIPS 140-3 • Bell-LaPadula MLS
 */
class SecurityBlueprintProtectiveMeasuresTest {

    private val secureRandom = SecureRandom()

    // ==========================================
    // 1. FIPS 140-3 Power-On KAT Vectors (Section 14.A)
    // ==========================================
    @Test
    fun testFips140PowerOnSelfTestPasses() {
        val result = FipsCryptographicSelfTest.runPowerOnSelfTest()
        assertTrue("FIPS 140-3 power-on self-test KAT vectors must pass", result)
    }

    // ==========================================
    // 2. Memory-Hard Scrypt KDF Hasher (Section 4.1 & 12.A)
    // ==========================================
    @Test
    fun testScryptKdfCredentialHashingAndVerification() {
        val password = "Secure#Passcode2026"
        val hash = CredentialSecurityPolicy.hashCredential(password)

        assertTrue("Hash format must adhere to scrypt:v1:<salt>:<hash>", hash.startsWith("scrypt:v1:"))
        val parts = hash.split(":")
        assertEquals("Hash must contain exactly 4 delimited components", 4, parts.size)
        assertEquals("Salt must be 16 bytes (32 hex characters)", 32, parts[2].length)
        assertEquals("Derived key must be 32 bytes (64 hex characters)", 64, parts[3].length)

        // Valid credential verification
        assertTrue("Matching candidate password must verify successfully",
            CredentialSecurityPolicy.verifyCredential(password, hash))

        // Invalid candidate must fail
        assertFalse("Incorrect password must fail verification",
            CredentialSecurityPolicy.verifyCredential("WrongPassword123!", hash))

        // Corrupted hash must fail safely
        assertFalse("Corrupted salt must fail verification safely",
            CredentialSecurityPolicy.verifyCredential(password, "scrypt:v1:00:ffff"))
        assertFalse("Malformed hash prefix must fail",
            CredentialSecurityPolicy.verifyCredential(password, "argon2:v1:salt:hash"))
    }

    // ==========================================
    // 3. Anti-Spray Credential Policy (Section 4.3 & 12.B)
    // ==========================================
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
        assertFalse(CredentialSecurityPolicy.validateCredentialPolicy("admin1234").isValid)

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

    // ==========================================
    // 4. Dual-Keyed Anti-Botnet Rate Limiter (Section 5)
    // ==========================================
    @Test
    fun testDualKeyedAntiBotnetRateLimiterLockout() {
        val limiter = CredentialSecurityPolicy.AntiBotnetRateLimiter(maxAttempts = 5, lockoutDurationMs = 60_000L)
        val clientIp = "192.168.43.50"
        val userKey = "user:dr_nitin"

        limiter.reset(clientIp)
        limiter.reset(userKey)

        // Under limit: not locked
        for (i in 1..4) {
            val status = limiter.recordFailedAttempt(clientIp)
            assertFalse("Attempt $i should not trigger lockout", status.isLocked)
        }

        // 5th attempt triggers lockout
        val lockedStatus = limiter.recordFailedAttempt(clientIp)
        assertTrue("Attempt 5 must trigger account/IP lockout", lockedStatus.isLocked)
        assertTrue("Remaining lockout duration must be positive", lockedStatus.remainingLockMs > 0)

        // isLocked check with dual-keys
        assertTrue("Dual-key checker must report locked when IP is locked", limiter.isLocked(clientIp, userKey))
        assertFalse("Independent user key must not be locked yet", limiter.checkStatus(userKey).isLocked)

        // Reset clears lockout
        limiter.reset(clientIp)
        assertFalse("Reset must release lockout", limiter.checkStatus(clientIp).isLocked)
    }

    // ==========================================
    // 5. Authenticated AES-256-GCM Buffer Crypto (Section 7.4 & 12.C)
    // ==========================================
    @Test
    fun testAuthenticatedAes256GcmCryptoRoundtrip() {
        val plaintext = "PATIENT_TRIAGE:URGENT:BLOOD_TYPE_O_NEG:LAT_18.5204_LON_73.8567".toByteArray(Charsets.UTF_8)
        val passphrase = "MasterPassphrase42!Enclave"

        val encrypted = CredentialSecurityPolicy.encryptBufferAesGcm(plaintext, passphrase)
        assertTrue("Ciphertext buffer must include salt + IV + GCM tag overhead (>= 44 bytes)",
            encrypted.size >= plaintext.size + 44)

        val decrypted = CredentialSecurityPolicy.decryptBufferAesGcm(encrypted, passphrase)
        assertArrayEquals("Decrypted payload must match original plaintext", plaintext, decrypted)

        // Tampering with ciphertext byte must fail authentication (AEAD integrity)
        val tampered = encrypted.clone()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()
        try {
            CredentialSecurityPolicy.decryptBufferAesGcm(tampered, passphrase)
            fail("Tampered ciphertext must fail authentication and throw AEAD exception")
        } catch (_: Exception) {
            // Expected
        }
    }

    // ==========================================
    // 6. DoD 5220.22-M Ephemeral File Shredding (Section 7.5 & NIST SP 800-88)
    // ==========================================
    @Test
    fun testEphemeralFileShredding() {
        val tempFile = File.createTempFile("sensitive_export_", ".tmp")
        tempFile.writeText("SECRET_TELEMETRY_LOGS_PATIENT_IDENTIFIERS_AND_GPS_BEACONS")
        assertTrue("Temp file must exist before shredding", tempFile.exists())

        val shredded = CredentialSecurityPolicy.shredEphemeralFile(tempFile)
        assertTrue("Shredding operation must report success", shredded)
        assertFalse("Shredded file must no longer exist on storage volume", tempFile.exists())
    }

    // ==========================================
    // 7. Active Deception Honeypots & Quarantine (Section 9)
    // ==========================================
    @Test
    fun testHoneypotCanaryPathDetectionAndQuarantine() {
        NetworkQuarantineManager.clearAll()

        // Canary reconnaissance traps must be detected
        assertTrue(NetworkQuarantineManager.isCanaryPath("/.env"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/.git/config"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/wp-admin"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/phpmyadmin"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/.aws/credentials"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/shell.php"))
        assertTrue(NetworkQuarantineManager.isCanaryPath("/admin.php"))

        // Legitimate app paths must NOT trigger canary
        assertFalse(NetworkQuarantineManager.isCanaryPath("/"))
        assertFalse(NetworkQuarantineManager.isCanaryPath("/download"))
        assertFalse(NetworkQuarantineManager.isCanaryPath("/index.html"))

        // Quarantine imposition
        val hostileIp = "192.168.43.99"
        assertFalse(NetworkQuarantineManager.isQuarantined(hostileIp))

        NetworkQuarantineManager.quarantineIp(hostileIp, "Recon probe hitting /.env")
        assertTrue("Hostile IP must be quarantined", NetworkQuarantineManager.isQuarantined(hostileIp))

        // Normal IP remains free
        assertFalse("Benign IP must not be quarantined", NetworkQuarantineManager.isQuarantined("192.168.43.10"))

        // Release
        NetworkQuarantineManager.releaseIp(hostileIp)
        assertFalse(NetworkQuarantineManager.isQuarantined(hostileIp))
    }

    // ==========================================
    // 8. Multi-Level Security (MLS) & Bell-LaPadula (Section 6 & 14.C)
    // ==========================================
    @Test
    fun testMultiLevelSecurityBellLaPadulaRules() {
        val civilian = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "civilian-01",
            role = MultiLevelSecurityPolicy.SystemRole.CIVILIAN
        )
        val doctor = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "doctor-nitin",
            role = MultiLevelSecurityPolicy.SystemRole.DOCTOR_CLINICIAN
        )
        val admin = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "commander-01",
            role = MultiLevelSecurityPolicy.SystemRole.ADMIN_OFFICER
        )

        // Simple Security Property: "No Read Up"
        assertTrue("Civilian can read UNCLASSIFIED public bulletin",
            MultiLevelSecurityPolicy.isReadPermitted(civilian, MultiLevelSecurityPolicy.SecurityClassification.UNCLASSIFIED))
        assertFalse("Civilian CANNOT read SECRET PHI medical records",
            MultiLevelSecurityPolicy.isReadPermitted(civilian, MultiLevelSecurityPolicy.SecurityClassification.SECRET))
        assertFalse("Civilian CANNOT read TOP_SECRET master keys",
            MultiLevelSecurityPolicy.isReadPermitted(civilian, MultiLevelSecurityPolicy.SecurityClassification.TOP_SECRET))

        assertTrue("Doctor can read UNCLASSIFIED bulletins",
            MultiLevelSecurityPolicy.isReadPermitted(doctor, MultiLevelSecurityPolicy.SecurityClassification.UNCLASSIFIED))
        assertTrue("Doctor can read SECRET PHI records",
            MultiLevelSecurityPolicy.isReadPermitted(doctor, MultiLevelSecurityPolicy.SecurityClassification.SECRET))
        assertFalse("Doctor CANNOT read TOP_SECRET root enclave keys",
            MultiLevelSecurityPolicy.isReadPermitted(doctor, MultiLevelSecurityPolicy.SecurityClassification.TOP_SECRET))

        assertTrue("Admin Officer can read TOP_SECRET master records",
            MultiLevelSecurityPolicy.isReadPermitted(admin, MultiLevelSecurityPolicy.SecurityClassification.TOP_SECRET))

        // Star Property (*-Property): "No Write Down"
        // Doctor (SECRET) cannot write down to UNCLASSIFIED channel (preventing leakage)
        assertFalse("Doctor cannot write sensitive PHI down to UNCLASSIFIED public channel",
            MultiLevelSecurityPolicy.isWritePermitted(doctor, MultiLevelSecurityPolicy.SecurityClassification.UNCLASSIFIED))
        assertTrue("Doctor can write to SECRET or higher classification channel",
            MultiLevelSecurityPolicy.isWritePermitted(doctor, MultiLevelSecurityPolicy.SecurityClassification.SECRET))

        // RBAC guard
        MultiLevelSecurityPolicy.requireRole(
            doctor,
            setOf(MultiLevelSecurityPolicy.SystemRole.DOCTOR_CLINICIAN, MultiLevelSecurityPolicy.SystemRole.ADMIN_OFFICER),
            "WRITE_PRESCRIPTION"
        )

        try {
            MultiLevelSecurityPolicy.requireRole(
                civilian,
                setOf(MultiLevelSecurityPolicy.SystemRole.DOCTOR_CLINICIAN),
                "WRITE_PRESCRIPTION"
            )
            fail("Civilian executing clinical action must throw AccessDeniedSecurityException")
        } catch (_: MultiLevelSecurityPolicy.AccessDeniedSecurityException) {
            // Expected
        }
    }

    // ==========================================
    // 9. Two-Person Integrity Dual-Control Quorum (Section 14.D / DoD 5210.41)
    // ==========================================
    @Test
    fun testTwoPersonIntegrityDualControlQuorum() {
        val (officerA_priv, officerA_pub) = generateEd25519KeyPair()
        val (officerB_priv, officerB_pub) = generateEd25519KeyPair()

        val action = TwoPersonIntegrityManager.StrategicAction.PANIC_PHYSICAL_PURGE
        val slipA = TwoPersonIntegrityManager.signEndorsement("OFFICER_ALPHA", officerA_priv, officerA_pub, action)
        val slipB = TwoPersonIntegrityManager.signEndorsement("OFFICER_BRAVO", officerB_priv, officerB_pub, action)

        // Valid dual control quorum
        val verified = TwoPersonIntegrityManager.verifyDualControlQuorum(slipA, slipB, action)
        assertTrue("Dual-officer authorized action must verify successfully", verified)

        // Self-authorization attempt (same officer twice) MUST FAIL
        val slipA_duplicate = TwoPersonIntegrityManager.signEndorsement("OFFICER_ALPHA", officerA_priv, officerA_pub, action)
        try {
            TwoPersonIntegrityManager.verifyDualControlQuorum(slipA, slipA_duplicate, action)
            fail("Self-authorization attempt must be algorithmically blocked")
        } catch (e: TwoPersonIntegrityManager.TpiQuorumViolationException) {
            assertTrue("Violation message must mention self-authorization",
                e.message?.contains("Self-authorization blocked") == true || e.message?.contains("Duplicate officer") == true)
        }

        // Wrong action endorsement must fail
        val slipA_wrong = TwoPersonIntegrityManager.signEndorsement(
            "OFFICER_ALPHA", officerA_priv, officerA_pub,
            TwoPersonIntegrityManager.StrategicAction.AUDIT_TRAIL_TRUNCATE
        )
        try {
            TwoPersonIntegrityManager.verifyDualControlQuorum(slipA_wrong, slipB, action)
            fail("Mismatched action endorsement must fail")
        } catch (_: TwoPersonIntegrityManager.TpiQuorumViolationException) {}
    }

    // ==========================================
    // 10. Citadel Defense Mode Lockdown (Section 14.E)
    // ==========================================
    @Test
    fun testCitadelDefenseModeLockdown() {
        CitadelDefenseManager.disengageCitadelDefense()

        // Normal DEFCON 5 allows remote ingress
        assertTrue("Remote peer must be permitted in DEFCON 5",
            CitadelDefenseManager.isIngressPermitted("192.168.43.10"))
        assertFalse("Database is not read-only in DEFCON 5", CitadelDefenseManager.isReadOnlyEnclave())

        // Engage Citadel Lockdown (DEFCON 1)
        CitadelDefenseManager.engageCitadelDefense("Active kinetic electronic warfare / hostile breach detected")
        assertEquals(CitadelDefenseManager.DefconLevel.DEFCON_1, CitadelDefenseManager.citadelState.value.defconLevel)
        assertTrue("Database enclave locked into read-only state", CitadelDefenseManager.isReadOnlyEnclave())

        // Remote ingress must be severed
        assertFalse("Remote IP ingress must be rejected during DEFCON 1",
            CitadelDefenseManager.isIngressPermitted("192.168.43.10"))
        assertFalse("Remote WAN IP ingress must be rejected during DEFCON 1",
            CitadelDefenseManager.isIngressPermitted("10.0.0.5"))

        // Loopback console MUST still be permitted
        assertTrue("Local loopback (127.0.0.1) console must be permitted in DEFCON 1",
            CitadelDefenseManager.isIngressPermitted("127.0.0.1"))
        assertTrue("Local loopback (::1) console must be permitted in DEFCON 1",
            CitadelDefenseManager.isIngressPermitted("::1"))

        // Disengage restores normal operations
        CitadelDefenseManager.disengageCitadelDefense()
        assertEquals(CitadelDefenseManager.DefconLevel.DEFCON_5, CitadelDefenseManager.citadelState.value.defconLevel)
        assertTrue("Remote peer allowed once DEFCON restored",
            CitadelDefenseManager.isIngressPermitted("192.168.43.10"))
    }

    // ==========================================
    // 11. Military-STIG HTTP Security Headers (Section 10)
    // ==========================================
    @Test
    fun testMilitaryStigSecurityHeadersPresent() {
        val headers = ApkPropagationServer.STIG_SECURITY_HEADERS
        assertTrue("Must enforce Strict-Transport-Security", headers.contains("Strict-Transport-Security"))
        assertTrue("Must enforce X-Frame-Options: DENY", headers.contains("X-Frame-Options: DENY"))
        assertTrue("Must enforce X-Content-Type-Options: nosniff", headers.contains("X-Content-Type-Options: nosniff"))
        assertTrue("Must enforce Referrer-Policy", headers.contains("Referrer-Policy: strict-origin-when-cross-origin"))
        assertTrue("Must enforce Content-Security-Policy", headers.contains("Content-Security-Policy: default-src 'self'"))
        assertFalse("Must NOT contain unsafe-eval in CSP", headers.contains("'unsafe-eval'"))
    }

    // ==========================================
    // 12. Constant-Time Digital Seal (Section 8.1 & 12.D)
    // ==========================================
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

    // ==========================================
    // 13. Mesh Rate Limiter (Section 5)
    // ==========================================
    @Test
    fun testMeshRateLimiterDualKeyProtection() {
        val limiter = MeshRateLimiter(peerLimitPerSec = 5, channelLimitPerSec = 10)
        val peerA = "192.168.43.10"
        val peerB = "192.168.43.11"

        for (i in 1..5) {
            assertTrue("Packet $i should be permitted", limiter.allowPacket(peerA, "GROUP_CHAT"))
        }

        assertFalse("Packet 6 from peerA should be throttled", limiter.allowPacket(peerA, "GROUP_CHAT"))
        assertTrue("PeerB should be allowed under independent peer key", limiter.allowPacket(peerB, "GROUP_CHAT"))
    }

    // ==========================================
    // 14. Native Memory Lock DoD Wipe (Section 14.B)
    // ==========================================
    @Test
    fun testNativeMemoryLockSecureWipeDoD() {
        val lock = NativeMemoryLock()
        val sensitiveBuffer = byteArrayOf(0x41, 0x42, 0x43, 0x44, 0x45, 0x46)
        lock.secureWipe(sensitiveBuffer)

        for (b in sensitiveBuffer) {
            assertTrue("Wiped buffer byte must be 0x00", b == 0.toByte())
        }
    }

    // ==========================================
    // 15. Strict Anti-SSRF Protection (Blueprint Section 7.1 & Step 11)
    // ==========================================
    @Test
    fun testSsrfProtectionValidator() {
        // Cloud metadata targets must be blocked
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://169.254.169.254/latest/meta-data").isValid)
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://metadata.google.internal/computeMetadata/v1").isValid)

        // Loopback must be blocked by default
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://127.0.0.1:8080/admin").isValid)
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://localhost:3000").isValid)
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://[::1]:9090").isValid)

        // Prohibited protocols (e.g. file://, ftp://)
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("file:///etc/passwd").isValid)
        assertFalse(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("gopher://127.0.0.1:25").isValid)

        // Valid remote mesh peers must be allowed
        assertTrue(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("http://192.168.43.10:8080/download").isValid)
        assertTrue(network.disaster.management.security.SsrfProtectionValidator.validateEndpointUrl("https://relief-mesh.org/api/sync").isValid)
    }

    // ==========================================
    // 16. Inline Threat IDS Detection (Blueprint Section 2.2 & Step 1)
    // ==========================================
    @Test
    fun testInlineThreatIdsDetection() {
        // SQL Injection signatures
        val sqli1 = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/?id=1 UNION SELECT null, password FROM users")
        assertTrue(sqli1.isThreat)
        assertEquals("SQL_INJECTION", sqli1.threatCategory)

        val sqli2 = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/login?name=' OR 1=1 --")
        assertTrue(sqli2.isThreat)

        // Cross-Site Scripting (XSS)
        val xss = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/search?q=<script>alert('pwned')</script>")
        assertTrue(xss.isThreat)
        assertEquals("CROSS_SITE_SCRIPTING", xss.threatCategory)

        // Directory Traversal
        val traversal = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/view?file=../../../../etc/passwd")
        assertTrue(traversal.isThreat)
        assertEquals("DIRECTORY_TRAVERSAL", traversal.threatCategory)

        // Remote Code Execution (RCE)
        val rce = network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/exec?cmd=;whoami")
        assertTrue(rce.isThreat)
        assertEquals("REMOTE_CODE_EXECUTION", rce.threatCategory)

        // Benign requests must NOT be flagged
        assertFalse(network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/download").isThreat)
        assertFalse(network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/").isThreat)
        assertFalse(network.disaster.management.security.ActiveAdversaryDefense.inspectPayload("/peer/status").isThreat)
    }

    // ==========================================
    // 17. Declarative Permissions Matrix (Blueprint Section 4.1 & Step 6)
    // ==========================================
    @Test
    fun testDeclarativePermissionsMatrix() {
        val civilian = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "civilian_user",
            role = MultiLevelSecurityPolicy.SystemRole.CIVILIAN
        )
        val doctor = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "doctor_user",
            role = MultiLevelSecurityPolicy.SystemRole.DOCTOR_CLINICIAN
        )
        val admin = MultiLevelSecurityPolicy.SubjectCredentials(
            actorId = "admin_user",
            role = MultiLevelSecurityPolicy.SystemRole.ADMIN_OFFICER
        )

        // Civilian has broadcast and SOS, lacks triage write
        assertTrue(MultiLevelSecurityPolicy.canDo(civilian.role, MultiLevelSecurityPolicy.Permission.ALERT_BROADCAST))
        assertTrue(MultiLevelSecurityPolicy.canDo(civilian.role, MultiLevelSecurityPolicy.Permission.SOS_TRIGGER))
        assertFalse(MultiLevelSecurityPolicy.canDo(civilian.role, MultiLevelSecurityPolicy.Permission.CASUALTY_TRIAGE_WRITE))

        // Doctor has triage write
        assertTrue(MultiLevelSecurityPolicy.canDo(doctor.role, MultiLevelSecurityPolicy.Permission.CASUALTY_TRIAGE_WRITE))

        // Admin has all permissions including purge
        assertTrue(MultiLevelSecurityPolicy.canDo(admin.role, MultiLevelSecurityPolicy.Permission.VAULT_PURGE_EXECUTE))
        assertTrue(MultiLevelSecurityPolicy.canDo(admin.role, MultiLevelSecurityPolicy.Permission.DEFCON_LOCKDOWN_CONTROL))

        // requirePermission assertion
        MultiLevelSecurityPolicy.requirePermission(doctor, MultiLevelSecurityPolicy.Permission.CASUALTY_TRIAGE_WRITE)

        try {
            MultiLevelSecurityPolicy.requirePermission(civilian, MultiLevelSecurityPolicy.Permission.VAULT_PURGE_EXECUTE)
            fail("Civilian executing purge must throw AccessDeniedSecurityException")
        } catch (_: MultiLevelSecurityPolicy.AccessDeniedSecurityException) {}
    }

    // ==========================================
    // 18. Decoy Honeytoken DB Generation (Blueprint Section 6.2 & Step 15)
    // ==========================================
    @Test
    fun testDecoyHoneytokenDatabaseGeneration() {
        val decoyBytes = network.disaster.management.security.ActiveAdversaryDefense.generateDecoyDatabaseBytes()
        assertTrue("Decoy payload must not be empty", decoyBytes.isNotEmpty())
        val decoyString = String(decoyBytes, Charsets.UTF_8)
        assertTrue("Decoy payload must contain honeytoken canary tracking token",
            decoyString.contains("HONEYTOKEN") || decoyString.contains("CANARY"))
    }

    private fun generateEd25519KeyPair(): Pair<ByteArray, ByteArray> {
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(secureRandom))
        val pair = gen.generateKeyPair()
        val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
        val pub = (pair.public as Ed25519PublicKeyParameters).encoded
        return Pair(priv, pub)
    }
}
