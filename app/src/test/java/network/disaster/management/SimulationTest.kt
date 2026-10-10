package network.disaster.management

import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.crypto.NativeMemoryLock
import network.disaster.management.crypto.OutOfOrderRatchetEngine
import network.disaster.management.model.CivilGroup
import network.disaster.management.routing.BundleStorageVault
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.storage.MediaChunkingEngine
import network.disaster.management.transport.acoustic.FastFskModem
import network.disaster.management.ui.GroupQrHelper
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Arrays
import kotlin.math.sin

class SimulationTest {

    private val secureRandom = SecureRandom()

    class MockBundleVault : BundleStorageVault {
        val storedBundles = mutableMapOf<String, ByteArray>()
        val poolAssignments = mutableMapOf<String, String>()

        override fun storeBundleWithSubkey(bundleId: ByteArray, poolType: String, payload: ByteArray): Boolean {
            val hex = bundleId.joinToString("") { "%02X".format(it) }
            storedBundles[hex] = payload
            poolAssignments[hex] = poolType
            return true
        }
    }

    @Test
    fun testNativeMemoryLockAndOutOfOrderRatchetSimulation() {
        val lock = NativeMemoryLock()
        val engineAlice = OutOfOrderRatchetEngine(lock)
        val engineBob = OutOfOrderRatchetEngine(lock)

        val initialChainKey = ByteArray(32).also { secureRandom.nextBytes(it) }

        val aliceState = OutOfOrderRatchetEngine.RatchetState(initialChainKey.clone(), 0)
        val bobState = OutOfOrderRatchetEngine.RatchetState(initialChainKey.clone(), 0)

        // Alice generates keys for messages 0, 1, 2, 3, 4
        val aliceKey0 = engineAlice.deriveMessageKey(aliceState, 0)
        val aliceKey1 = engineAlice.deriveMessageKey(aliceState, 1)
        val aliceKey2 = engineAlice.deriveMessageKey(aliceState, 2)
        val aliceKey3 = engineAlice.deriveMessageKey(aliceState, 3)
        val aliceKey4 = engineAlice.deriveMessageKey(aliceState, 4)

        // Bob receives out-of-order: 0, then 3 (skipping 1 and 2), then 1, then 4, then 2
        val bobKey0 = engineBob.getOrDeriveKey(bobState, 0)
        assertNotNull(bobKey0)
        assertArrayEquals("Message 0 key must match", aliceKey0, bobKey0)

        // Bob receives message 3: engine will skip 1 and 2 and pin them into locked memory
        val bobKey3 = engineBob.getOrDeriveKey(bobState, 3)
        assertNotNull(bobKey3)
        assertArrayEquals("Message 3 key must match", aliceKey3, bobKey3)

        // Bob receives out-of-order message 1: should consume skipped key
        val bobKey1 = engineBob.getOrDeriveKey(bobState, 1)
        assertNotNull(bobKey1)
        assertArrayEquals("Out-of-order message 1 key must match", aliceKey1, bobKey1)

        // Consuming message 1 again should return null (already shredded / consumed)
        assertNull("Consumed key must not be re-usable", engineBob.getOrDeriveKey(bobState, 1))

        // Bob receives message 4
        val bobKey4 = engineBob.getOrDeriveKey(bobState, 4)
        assertNotNull(bobKey4)
        assertArrayEquals("Message 4 key must match", aliceKey4, bobKey4)

        // Bob receives out-of-order message 2
        val bobKey2 = engineBob.getOrDeriveKey(bobState, 2)
        assertNotNull(bobKey2)
        assertArrayEquals("Out-of-order message 2 key must match", aliceKey2, bobKey2)
    }

    @Test
    fun testTriPoolRouterEmergencyRoutingSimulation() {
        val vault = MockBundleVault()
        val localTag = ByteArray(16) { 0x01 }
        val router = TriPoolRouter(vault, localTag)

        // 1. Test EmergencyEnvelope with various hop limits (including 1, 4, 8, 10, 15)
        for (hopLimit in listOf(1, 4, 8, 10, 15)) {
            val shortId = ByteArray(8).also { secureRandom.nextBytes(it) }
            val env = EnvelopeFormat.EmergencyEnvelope(
                hopLimit = hopLimit.toByte(),
                ttlExpirySeconds = 3600,
                shortBundleId = shortId,
                ephemeralX25519Pub = ByteArray(32),
                nonce = ByteArray(8),
                ciphertext = "DISTRESS: CIVILIANS TRAPPED".toByteArray(),
                poly1305Tag = ByteArray(16)
            )
            val serialized = EnvelopeFormat.serializeEmergency(env)
            val verdict = router.processIngressPacket(serialized)

            assertEquals("Emergency envelope must route to POOL_A regardless of hop limit ($hopLimit)",
                TriPoolRouter.RouteVerdict.ACCEPTED_POOL_A, verdict)

            // Verify stored bundle is padded to uniform 4,096 invariant
            val hexKey = shortId.copyOf(32).joinToString("") { "%02X".format(it) }
            val stored = vault.storedBundles[hexKey]
            assertNotNull("Emergency bundle must be stored in vault", stored)
            assertEquals("Stored emergency bundle must be uniform 4096 bytes", EnvelopeFormat.TIER_S_SIZE, stored!!.size)
            assertEquals("Stored pool assignment must be POOL_A", "POOL_A", vault.poolAssignments[hexKey])
        }

        // 2. Test Tier1Envelope with FLAG_EMERGENCY
        val bundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val tier1Emergency = EnvelopeFormat.Tier1Envelope(
            hopLimit = 7.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 2,
            powNonce = 0,
            bundleId = bundleId,
            rollingRecipientTag = localTag,
            ephemeralX25519Pub = ByteArray(32),
            mlKemCiphertext = ByteArray(1088),
            nonce = ByteArray(12),
            bucketLength = 50,
            encryptedBody = ByteArray(2864),
            macTag = ByteArray(16),
            flags = (EnvelopeFormat.FLAG_ENCRYPTED.toInt() or EnvelopeFormat.FLAG_EMERGENCY.toInt()).toByte()
        )
        val serializedEmergencyTier1 = EnvelopeFormat.serializeTier1(tier1Emergency)
        val verdictTier1Emergency = router.processIngressPacket(serializedEmergencyTier1)
        assertEquals("Tier1 envelope with FLAG_EMERGENCY must route to POOL_A without requiring high PoW",
            TriPoolRouter.RouteVerdict.ACCEPTED_POOL_A, verdictTier1Emergency)

        // 3. Test File Chunk with FLAG_FILE_CHUNK
        val chunkBundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val chunkEnv = EnvelopeFormat.Tier1Envelope(
            hopLimit = 7.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 0,
            powNonce = 0,
            bundleId = chunkBundleId,
            rollingRecipientTag = localTag,
            ephemeralX25519Pub = ByteArray(32),
            mlKemCiphertext = ByteArray(1088),
            nonce = ByteArray(12),
            bucketLength = 100,
            encryptedBody = ByteArray(2864),
            macTag = ByteArray(16),
            flags = (EnvelopeFormat.FLAG_ENCRYPTED.toInt() or EnvelopeFormat.FLAG_FILE_CHUNK.toInt()).toByte()
        )
        val serializedChunk = EnvelopeFormat.serializeTier1(chunkEnv)
        val verdictChunk = router.processIngressPacket(serializedChunk)
        assertEquals("File chunk envelope must route to POOL_C",
            TriPoolRouter.RouteVerdict.ACCEPTED_POOL_C, verdictChunk)

        // 4. Test Normal Text with Valid PoW -> POOL_B
        val textBundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val textEnv = EnvelopeFormat.Tier1Envelope(
            hopLimit = 5.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 4,
            powNonce = 0,
            bundleId = textBundleId,
            rollingRecipientTag = localTag,
            ephemeralX25519Pub = ByteArray(32),
            mlKemCiphertext = ByteArray(1088),
            nonce = ByteArray(12),
            bucketLength = 40,
            encryptedBody = ByteArray(2864),
            macTag = ByteArray(16),
            flags = EnvelopeFormat.FLAG_ENCRYPTED
        )
        val rawTextBytes = EnvelopeFormat.serializeTier1(textEnv)
        val solvedNonce = EnvelopeFormat.solvePoW(rawTextBytes, 4)
        textEnv.powNonce = solvedNonce
        val solvedTextBytes = EnvelopeFormat.serializeTier1(textEnv)
        val verdictText = router.processIngressPacket(solvedTextBytes)
        assertEquals("Text envelope with valid PoW must route to POOL_B",
            TriPoolRouter.RouteVerdict.ACCEPTED_POOL_B, verdictText)

        // 5. Test Text with Unsolved PoW -> DROPPED
        textEnv.powNonce = 0
        textEnv.powDifficulty = 4
        val unsolvedTextBytes = EnvelopeFormat.serializeTier1(textEnv)
        val verdictUnsolved = router.processIngressPacket(unsolvedTextBytes)
        assertEquals("Text envelope with invalid PoW must be DROPPED",
            TriPoolRouter.RouteVerdict.DROPPED, verdictUnsolved)
    }

    @Test
    fun testGroupQrStringSanitizationAndImport() {
        val key = ByteArray(32).also { secureRandom.nextBytes(it) }
        // Test group with pipe characters in description
        val originalGroup = CivilGroup(
            id = "GRP_TEST_PIPE",
            name = "Search | Rescue Team",
            description = "Sector 4 coordination | triage point A | shelter B",
            isBroadcast = false,
            secretKey = key,
            createdEpoch = 1700000000000L
        )

        val qrString = GroupQrHelper.formatGroupToQrString(originalGroup)
        assertTrue(qrString.startsWith("DISASTER_GRP:"))

        // Verify simulated parsing logic from LowWearCryptoShredder
        val raw = qrString.removePrefix("DISASTER_GRP:")
        val parts = raw.split("|")
        assertTrue("Parts count should be at least 4", parts.size >= 4)
        val id = parts[0]
        val name = parts[1]
        val isBroadcast = if (parts.size >= 5) parts.last() == "1" else false
        val keyBase64 = if (parts.size >= 5) parts[parts.size - 2] else parts[3]
        val desc = if (parts.size > 5) parts.subList(2, parts.size - 2).joinToString("|") else parts[2]

        val decodedKey = java.util.Base64.getDecoder().decode(keyBase64)

        assertEquals("GRP_TEST_PIPE", id)
        assertArrayEquals("Secret key must match", key, decodedKey)
        assertFalse(isBroadcast)
        assertEquals("Search - Rescue Team", name)
        assertEquals("Sector 4 coordination - triage point A - shelter B", desc)
    }

    @Test
    fun testFastFskModemContinuousPhaseDemodulation() {
        val modem = FastFskModem()
        val sampleRate = FastFskModem.SAMPLE_RATE
        val samplesPerBit = FastFskModem.SAMPLES_PER_BIT

        val testPayload = byteArrayOf(0xAA.toByte(), 0x55.toByte(), 0xDE.toByte(), 0xAD.toByte())

        val bits = mutableListOf<Int>()
        for (b in testPayload) {
            for (bitIdx in 7 downTo 0) {
                val bit = (b.toInt() ushr bitIdx) and 0x01
                val freq = if (bit == 1) FastFskModem.FREQ_MARK else FastFskModem.FREQ_SPACE
                val samples = ShortArray(samplesPerBit)
                for (s in 0 until samplesPerBit) {
                    val angle = 2.0 * Math.PI * s * (freq / sampleRate)
                    samples[s] = (sin(angle) * Short.MAX_VALUE).toInt().toShort()
                }
                bits.add(modem.demodulateSampleWindow(samples, 0, samplesPerBit))
            }
        }

        val decoded = ByteArray(testPayload.size)
        for (i in testPayload.indices) {
            var b = 0
            for (j in 0 until 8) {
                b = (b shl 1) or bits[i * 8 + j]
            }
            decoded[i] = b.toByte()
        }
        assertArrayEquals(testPayload, decoded)
    }

    @Test
    fun testMediaChunkingAndReassemblyFidelity() {
        val engine = MediaChunkingEngine()
        val originalBytes = ByteArray(8500).also { secureRandom.nextBytes(it) }

        val (manifest, packages) = engine.chunkBytes(originalBytes, "triage_map.pdf", "application/pdf")
        assertEquals(8500L, manifest.fileSize)
        assertEquals(4, packages.size)

        val reassembled = ByteArrayOutputStream()
        val bodyOffset = 1216
        for (pkg in packages) {
            val innerPayload = pkg.packet4k.copyOfRange(bodyOffset, bodyOffset + 30 + pkg.chunkData.size)
            val parsed = engine.parseChunkHeader(innerPayload)
            assertNotNull(parsed)
            reassembled.write(parsed!!.data)
        }
        assertArrayEquals(originalBytes, reassembled.toByteArray())
    }
}
