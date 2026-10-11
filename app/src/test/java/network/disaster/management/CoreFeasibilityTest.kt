package network.disaster.management

import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.crypto.HybridEngine
import network.disaster.management.routing.QuorumElevationEngine
import network.disaster.management.routing.TimeConsensusManager
import network.disaster.management.transport.infrastructure.adversarial.DnsTunnelingExfiltrator
import network.disaster.management.transport.optical.FountainQrCodec
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import java.util.Arrays

class CoreFeasibilityTest {

    private val secureRandom = SecureRandom()

    @Test
    fun testEnvelopeTier1SerializationAndPoW() {
        val bundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val recipientTag = ByteArray(16).also { secureRandom.nextBytes(it) }
        val x25519 = ByteArray(32).also { secureRandom.nextBytes(it) }
        val mlKem = ByteArray(1088).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val body = ByteArray(2864).also { secureRandom.nextBytes(it) }
        val mac = ByteArray(16).also { secureRandom.nextBytes(it) }

        val now = System.currentTimeMillis()
        val envelope = EnvelopeFormat.Tier1Envelope(
            hopLimit = 5.toByte(),
            creationEpoch = now,
            ttlExpiry = now + 86400000L,
            powDifficulty = 4,
            powNonce = 0,
            bundleId = bundleId,
            rollingRecipientTag = recipientTag,
            ephemeralX25519Pub = x25519,
            mlKemCiphertext = mlKem,
            nonce = nonce,
            bucketLength = body.size,
            encryptedBody = body,
            macTag = mac
        )

        // Verify exact 4096-byte invariant size
        val serialized = EnvelopeFormat.serializeTier1(envelope)
        assertEquals(4096, serialized.size)

        // Solve PoW difficulty 4
        val solvedNonce = EnvelopeFormat.solvePoW(serialized, 4)
        envelope.powNonce = solvedNonce
        val solvedBytes = EnvelopeFormat.serializeTier1(envelope)

        // Deserialize and check equality
        val deserialized = EnvelopeFormat.deserializeTier1(solvedBytes)
        assertNotNull(deserialized)
        assertEquals(5.toByte(), deserialized!!.hopLimit)
        assertEquals(now, deserialized.creationEpoch)
        assertEquals(4, deserialized.powDifficulty)
        assertEquals(solvedNonce, deserialized.powNonce)
        assertArrayEquals(bundleId, deserialized.bundleId)
        assertArrayEquals(recipientTag, deserialized.rollingRecipientTag)
        assertArrayEquals(x25519, deserialized.ephemeralX25519Pub)
        assertArrayEquals(mlKem, deserialized.mlKemCiphertext)
        assertArrayEquals(nonce, deserialized.nonce)
        assertArrayEquals(body, deserialized.encryptedBody)
        assertArrayEquals(mac, deserialized.macTag)
    }

    @Test
    fun testEnvelopeEmergencySerialization() {
        val shortId = ByteArray(8).also { secureRandom.nextBytes(it) }
        val x25519 = ByteArray(32).also { secureRandom.nextBytes(it) }
        val nonce = ByteArray(8).also { secureRandom.nextBytes(it) }
        val ciphertext = "DISTRESS-CALL-ALPHA".toByteArray(Charsets.UTF_8)
        val tag = ByteArray(16).also { secureRandom.nextBytes(it) }

        val env = EnvelopeFormat.EmergencyEnvelope(
            hopLimit = 10.toByte(),
            ttlExpirySeconds = 7200,
            shortBundleId = shortId,
            ephemeralX25519Pub = x25519,
            nonce = nonce,
            ciphertext = ciphertext,
            poly1305Tag = tag
        )

        val serialized = EnvelopeFormat.serializeEmergency(env)
        val deserialized = EnvelopeFormat.deserializeEmergency(serialized)

        assertNotNull(deserialized)
        assertEquals(10.toByte(), deserialized!!.hopLimit)
        assertEquals(7200, deserialized.ttlExpirySeconds)
        assertArrayEquals(shortId, deserialized.shortBundleId)
        assertArrayEquals(x25519, deserialized.ephemeralX25519Pub)
        assertArrayEquals(nonce, deserialized.nonce)
        assertArrayEquals(ciphertext, deserialized.ciphertext)
        assertArrayEquals(tag, deserialized.poly1305Tag)
    }

    @Test
    fun testHybridPostQuantumKeyEncapsulationAndBucketEncryption() {
        val engine = HybridEngine()

        // Alice (Sender) and Bob (Receiver)
        val aliceX = engine.generateX25519KeyPair()
        val bobX = engine.generateX25519KeyPair()
        val bobKyber = engine.generateKyber768KeyPair()

        // Key encapsulation
        val (pqSecretAlice, pqCiphertext) = engine.encapsulateKyber768(bobKyber.public)
        val classicalSecretAlice = engine.calculateX25519Agreement(aliceX.private, bobX.public)

        val pqSecretBob = engine.decapsulateKyber768(bobKyber.private, pqCiphertext)
        val classicalSecretBob = engine.calculateX25519Agreement(bobX.private, aliceX.public)

        assertArrayEquals(classicalSecretAlice, classicalSecretBob)
        assertArrayEquals(pqSecretAlice, pqSecretBob)

        // Hybrid key derivation
        val salt = ByteArray(16).also { secureRandom.nextBytes(it) }
        val keyAlice = engine.deriveHybridSharedKey(classicalSecretAlice, pqSecretAlice, salt)
        val keyBob = engine.deriveHybridSharedKey(classicalSecretBob, pqSecretBob, salt)
        assertArrayEquals(keyAlice, keyBob)

        // Encrypt message inside 4096-byte invariant bucket
        val originalMessage = "MEDICAL SUPPLY EVACUATION COORDINATES: 37.7749,-122.4194".toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "BUNDLE-AUTH-DATA".toByteArray(Charsets.UTF_8)

        val (ciphertext, macTag) = engine.encryptPayloadTier1(keyAlice, nonce, aad, originalMessage)
        assertEquals(2864, ciphertext.size)
        assertEquals(16, macTag.size)

        // Decrypt and verify
        val decrypted = engine.decryptPayloadTier1(keyBob, nonce, aad, ciphertext, macTag)
        assertNotNull(decrypted)
        assertArrayEquals(originalMessage, decrypted)
    }

    @Test
    fun testFountainQrCodecEncodingAndPeelingDecoder() {
        val codec = FountainQrCodec()
        val test4k = ByteArray(4096).also { secureRandom.nextBytes(it) }

        val decoder = FountainQrCodec.LTDecoder()
        var seed = 100
        var totalDroplets = 0

        // In Luby Transform, peeling decoder reaches full rank around 50-90 droplets
        while (!decoder.isComplete && totalDroplets < 300) {
            val droplet = codec.createDroplet(test4k, seed++)
            val serialized = codec.serializeForQr(droplet)
            val parsedDroplet = codec.deserializeFromQr(serialized)
            assertNotNull(parsedDroplet)
            decoder.addDroplet(parsedDroplet!!)
            totalDroplets++
        }

        assertTrue("Fountain decoder failed to reconstruct within droplet limit: droplets=$totalDroplets", decoder.isComplete)
        val reconstructed = decoder.reconstruct()
        assertNotNull(reconstructed)
        assertArrayEquals(test4k, reconstructed)
    }

    @Test
    fun testBase32RoundTrip() {
        val exfiltrator = DnsTunnelingExfiltrator()
        for (len in listOf(0, 1, 4, 7, 10, 16, 32, 64, 128)) {
            val original = ByteArray(len).also { secureRandom.nextBytes(it) }
            val b32 = exfiltrator.toBase32(original)
            val restored = exfiltrator.fromBase32(b32)
            assertArrayEquals("Failed for length $len", original, restored)
        }
    }

    @Test
    fun testQuorumElevationMultiWitnessVerification() {
        fun genEdKeyPair(): Pair<ByteArray, ByteArray> {
            val gen = Ed25519KeyPairGenerator()
            gen.init(Ed25519KeyGenerationParameters(secureRandom))
            val pair = gen.generateKeyPair()
            val priv = (pair.private as Ed25519PrivateKeyParameters).encoded
            val pub = (pair.public as Ed25519PublicKeyParameters).encoded
            return Pair(priv, pub)
        }

        val w1 = genEdKeyPair()
        val w2 = genEdKeyPair()
        val w3 = genEdKeyPair()

        val engine1 = QuorumElevationEngine(w1.first, w1.second)
        val engine2 = QuorumElevationEngine(w2.first, w2.second)
        val engine3 = QuorumElevationEngine(w3.first, w3.second)

        val fileHash = ByteArray(32).also { secureRandom.nextBytes(it) }
        val sharedExpiry = System.currentTimeMillis() + 3600000L

        val s1 = engine1.createLocalEndorsement(fileHash, sharedExpiry)
        val s2 = engine2.createLocalEndorsement(fileHash, sharedExpiry)
        val s3 = engine3.createLocalEndorsement(fileHash, sharedExpiry)

        val cert = engine1.assembleQuorumCertificate(listOf(s1, s2, s3), fileHash)
        val valid = engine1.verifyQuorumCertificate(cert, fileHash)
        assertTrue("Quorum cert should be valid", valid)

        // Invalid file hash check
        val fakeHash = ByteArray(32).also { secureRandom.nextBytes(it) }
        val invalid = engine1.verifyQuorumCertificate(cert, fakeHash)
        assertFalse("Quorum cert must be invalid with wrong hash", invalid)
    }

    @Test
    fun testTimeConsensusManager() {
        val manager = TimeConsensusManager()
        val now = System.currentTimeMillis()

        manager.recordPeerTimestamp(now + 5000L)
        manager.recordPeerTimestamp(now - 3000L)
        manager.recordPeerTimestamp(now + 2000L)
        manager.recordPeerTimestamp(now + 1000L)

        val consensusHour = manager.getConsensusEpochHour()
        assertEquals(now / 3600000L, consensusHour)
    }

    @Test
    fun testMediaChunkingAndInvariantProfiles() {
        val engine = network.disaster.management.storage.MediaChunkingEngine()

        // Simulate a 10,000-byte video or image file
        val rawMedia = ByteArray(10000).also { secureRandom.nextBytes(it) }
        val fileName = "evacuation_map.png"
        val mimeType = "image/png"

        val (manifest, packages) = engine.chunkBytes(rawMedia, fileName, mimeType)

        assertEquals(fileName, manifest.fileName)
        assertEquals(10000L, manifest.fileSize)
        assertEquals(mimeType, manifest.mimeType)
        // 10000 / 2800 = 4 chunks (ceil)
        assertEquals(4, manifest.totalChunks)
        assertEquals(4, packages.size)

        val reassembledList = mutableListOf<ByteArray>()

        for (pkg in packages) {
            // Verify every slice is formatted inside an exact 4,096-byte invariant profile
            assertEquals(4096, pkg.packet4k.size)

            // Parse from packet4k body
            val bodyOffset = 2 + 1 + 1 + 1 + 3 + 8 + 8 + 4 + 4 + 32 + 16 + 32 + 1088 + 12 + 4 // 1216 bytes
            val innerPayload = pkg.packet4k.copyOfRange(bodyOffset, bodyOffset + 30 + pkg.chunkData.size)
            val parsedHeader = engine.parseChunkHeader(innerPayload)

            assertNotNull("Parsed chunk header must be valid", parsedHeader)
            assertEquals(manifest.fileId, parsedHeader!!.fileId)
            assertEquals(4, parsedHeader.totalChunks)
            assertTrue(Arrays.equals(pkg.chunkData, parsedHeader.data))

            reassembledList.add(parsedHeader.data)
        }

        // Verify byte-for-byte fidelity of reassembled stream
        val fullReassembled = java.io.ByteArrayOutputStream()
        for (chunk in reassembledList) {
            fullReassembled.write(chunk)
        }
        val reconstructedBytes = fullReassembled.toByteArray()
        assertEquals(10000, reconstructedBytes.size)
        assertTrue("Reconstructed media must match original bytes exactly", Arrays.equals(rawMedia, reconstructedBytes))
    }

    @Test
    fun testCivilGroupAndDataModels() {
        val key = ByteArray(32).also { secureRandom.nextBytes(it) }
        val group = network.disaster.management.model.CivilGroup(
            id = "GRP_TEST_1",
            name = "Test Medical Group",
            description = "Medical triage team",
            secretKey = key,
            isBroadcast = false,
            createdEpoch = System.currentTimeMillis()
        )

        assertEquals("GRP_TEST_1", group.id)
        assertFalse(group.isBroadcast)

        val msg = network.disaster.management.model.FeedMessage(
            id = "MSG_1",
            groupId = group.id,
            senderName = "Operator-1",
            content = "Medical supplies received",
            type = network.disaster.management.model.MessageType.TEXT,
            timestamp = System.currentTimeMillis(),
            isEmergency = false
        )
        assertEquals(network.disaster.management.model.MessageType.TEXT, msg.type)
        assertFalse(msg.isEmergency)
    }

    @Test
    fun testUserIdentitySanitizationAndDefaultNames() {
        val manager = network.disaster.management.storage.UserIdentityManager

        // Test default user name
        val defaultName = manager.getDefaultUserName()
        assertTrue("Default name must start with Civilian-", defaultName.startsWith("Civilian-"))

        // Test sanitization
        assertEquals("Medic-1", manager.sanitizeName("  Medic-1  "))
        assertEquals("Sarah Connor", manager.sanitizeName("Sarah Connor\n\r"))
        assertEquals("CleanName", manager.sanitizeName("\"@CleanName:\""))

        // Test max length truncation
        val longName = "A".repeat(50)
        val sanitizedLong = manager.sanitizeName(longName)
        assertEquals(24, sanitizedLong.length)

        // Test suggestions
        val suggestions = manager.getSuggestedCallsigns()
        assertTrue("Suggestions must not be empty", suggestions.isNotEmpty())
        assertTrue("Suggestions must contain default name", suggestions.contains(defaultName))
    }
}
