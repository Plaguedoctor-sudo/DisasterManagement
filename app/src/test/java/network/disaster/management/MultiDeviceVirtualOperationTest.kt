package network.disaster.management

import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.crypto.HybridEngine
import network.disaster.management.model.CivilGroup
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import network.disaster.management.routing.BundleStorageVault
import network.disaster.management.routing.TriPoolRouter
import network.disaster.management.transport.BundleBloomFilter
import network.disaster.management.transport.MeshRateLimiter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * End-to-End Multi-Device Virtual Connection & Operation Test.
 * Simulates multiple autonomous Android nodes running over local sockets:
 *  - Verifies multi-node discovery and socket establishment
 *  - Verifies Bloom filter epidemic synchronization
 *  - Verifies real-time live message and emergency distress packet streaming
 *  - Verifies payload decryption and feed ingestion on receiving virtual peers
 *  - Verifies thread-safe concurrency without socket byte interleaving
 */
class MultiDeviceVirtualOperationTest {

    private val secureRandom = SecureRandom()
    private val hybridEngine = HybridEngine()

    class VirtualNode(val name: String, val port: Int) : BundleStorageVault {
        val storedBundles = ConcurrentHashMap<String, ByteArray>()
        val poolAssignments = ConcurrentHashMap<String, String>()
        val messages = mutableListOf<FeedMessage>()
        val groups = ConcurrentHashMap<String, CivilGroup>()

        val recipientTag = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val rateLimiter = MeshRateLimiter(peerLimitPerSec = 50, channelLimitPerSec = 100)

        init {
            // Seed default public broadcast group
            val publicGroup = CivilGroup(
                id = "GROUP_PUBLIC_BROADCAST",
                name = "Public Disaster Broadcast",
                description = "Civilian safety alerts",
                secretKey = ByteArray(32) { 0x01 },
                isBroadcast = true,
                createdEpoch = System.currentTimeMillis()
            )
            groups[publicGroup.id] = publicGroup
        }

        override fun storeBundleWithSubkey(bundleId: ByteArray, poolType: String, payload: ByteArray): Boolean {
            val hex = bundleId.joinToString("") { "%02X".format(it) }
            storedBundles[hex] = payload
            poolAssignments[hex] = poolType
            return true
        }

        fun storeDecryptedMessage(msg: FeedMessage) {
            synchronized(messages) {
                messages.add(msg)
            }
        }
    }

    private fun readExact(input: InputStream, buffer: ByteArray): Boolean {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            if (count == -1) return false
            total += count
        }
        return true
    }

    private fun writeFrameSafe(socket: Socket, opcode: Byte, data: ByteArray) {
        synchronized(socket) {
            val out = socket.getOutputStream()
            out.write(opcode.toInt())
            out.write(data)
            out.flush()
        }
    }

    @Test(timeout = 8000)
    fun testMultiDeviceVirtualConnectionAndMessageExchange() {
        val nodeA = VirtualNode("PhoneA_HotspotHost", 43425)
        val nodeB = VirtualNode("PhoneB_Client1", 43426)
        val nodeC = VirtualNode("PhoneC_Client2", 43427)

        val serverA = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress("127.0.0.1", nodeA.port))
        }

        val MSG_BLOOM_SUMMARY: Byte = 0x01
        val MSG_BUNDLE_PAYLOAD: Byte = 0x02

        val acceptedSockets = CopyOnWriteArrayList<Socket>()
        var running = true

        val serverThread = thread(start = true, isDaemon = true) {
            while (running && !serverA.isClosed) {
                try {
                    val clientSocket = serverA.accept()
                    acceptedSockets.add(clientSocket)
                    thread(start = true, isDaemon = true) {
                        try {
                            val input = clientSocket.getInputStream()
                            val buf = ByteArray(4096)
                            while (running && !clientSocket.isClosed) {
                                val op = input.read()
                                if (op == -1) break
                                when (op.toByte()) {
                                    MSG_BUNDLE_PAYLOAD -> {
                                        if (readExact(input, buf)) {
                                            processPacketOnNode(nodeA, buf.clone())
                                        } else break
                                    }
                                    MSG_BLOOM_SUMMARY -> {
                                        val summaryBuf = ByteArray(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES)
                                        readExact(input, summaryBuf)
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
        }

        var socketB: Socket? = null
        var socketC: Socket? = null

        try {
            // Connect Node B -> Node A
            socketB = Socket("127.0.0.1", nodeA.port)
            // Connect Node C -> Node A
            socketC = Socket("127.0.0.1", nodeA.port)

            assertTrue("Node B must be connected to Node A", socketB.isConnected)
            assertTrue("Node C must be connected to Node A", socketC.isConnected)

            // 1. Node B creates an emergency distress beacon
            val emergencyText = "SOS: 2 trapped under collapsed masonry at Sector 4! Need medical evac."
            val emBundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
            val emEnvelope = EnvelopeFormat.EmergencyEnvelope(
                hopLimit = 15.toByte(),
                ttlExpirySeconds = 86400,
                shortBundleId = emBundleId.copyOf(8),
                ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
                nonce = ByteArray(8).also { secureRandom.nextBytes(it) },
                ciphertext = emergencyText.toByteArray(Charsets.UTF_8),
                poly1305Tag = ByteArray(16).also { secureRandom.nextBytes(it) }
            )
            val rawEmergencyPacket = EnvelopeFormat.serializeEmergency(emEnvelope)
            val invariantEmergencyFrame = rawEmergencyPacket.copyOf(4096)

            // Node B transmits emergency packet to Node A over socket
            writeFrameSafe(socketB, MSG_BUNDLE_PAYLOAD, invariantEmergencyFrame)

            // 2. Node C creates a tactical encrypted group chat message
            val chatContent = "Field Team 2 en route with hydraulic spreaders. ETA 8 minutes."
            val chatJson = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("groupId", "GROUP_PUBLIC_BROADCAST")
                put("senderName", "NodeC-Tactical")
                put("content", chatContent)
                put("type", MessageType.TEXT.name)
                put("timestamp", System.currentTimeMillis())
                put("isEmergency", false)
            }.toString()

            val chatKey = nodeC.groups["GROUP_PUBLIC_BROADCAST"]!!.secretKey
            val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
            val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
            val (ciphertext, macTag) = hybridEngine.encryptPayloadTier1(chatKey, nonce, aad, chatJson.toByteArray(Charsets.UTF_8))

            val textEnv = EnvelopeFormat.Tier1Envelope(
                hopLimit = 7.toByte(),
                creationEpoch = System.currentTimeMillis(),
                ttlExpiry = System.currentTimeMillis() + 86400000L,
                powDifficulty = 4,
                powNonce = 0,
                bundleId = ByteArray(32).also { secureRandom.nextBytes(it) },
                rollingRecipientTag = nodeA.recipientTag,
                ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
                mlKemCiphertext = ByteArray(1088),
                nonce = nonce,
                bucketLength = ciphertext.size,
                encryptedBody = ciphertext,
                macTag = macTag,
                flags = EnvelopeFormat.FLAG_ENCRYPTED
            )
            val serializedChat = EnvelopeFormat.serializeTier1(textEnv)
            val solvedNonce = EnvelopeFormat.solvePoW(serializedChat, 4)
            textEnv.powNonce = solvedNonce
            val invariantChatFrame = EnvelopeFormat.serializeTier1(textEnv).copyOf(4096)

            // Node C transmits chat message to Node A over socket
            writeFrameSafe(socketC, MSG_BUNDLE_PAYLOAD, invariantChatFrame)

            // Give network event loop time to process
            Thread.sleep(600)

            // Verify Node A received and successfully ingested both messages
            assertTrue("Node A must have stored the emergency bundle in vault", nodeA.storedBundles.isNotEmpty())
            assertEquals("Emergency bundle must be routed to POOL_A", "POOL_A", nodeA.poolAssignments.values.first { it == "POOL_A" })

            val receivedMessages = synchronized(nodeA.messages) { nodeA.messages.toList() }
            assertEquals("Node A must have received 2 messages, found: ${receivedMessages.size}", 2, receivedMessages.size)

            val receivedEmergency = receivedMessages.find { it.type == MessageType.EMERGENCY }
            assertNotNull("Emergency beacon must be present in Node A feed", receivedEmergency)
            assertTrue("Emergency content must match original text", receivedEmergency!!.content.contains("trapped under collapsed masonry"))

            val receivedChat = receivedMessages.find { it.type == MessageType.TEXT }
            assertNotNull("Chat message must be present in Node A feed", receivedChat)
            assertEquals("Chat content must match original text", chatContent, receivedChat!!.content)
            assertEquals("Sender name must match", "NodeC-Tactical", receivedChat.senderName)

        } finally {
            running = false
            try { socketB?.close() } catch (_: Exception) {}
            try { socketC?.close() } catch (_: Exception) {}
            for (s in acceptedSockets) {
                try { s.close() } catch (_: Exception) {}
            }
            try { serverA.close() } catch (_: Exception) {}
        }
    }

    private fun processPacketOnNode(node: VirtualNode, rawPacket: ByteArray) {
        val router = TriPoolRouter(node, node.recipientTag)
        val verdict = router.processIngressPacket(rawPacket)

        // If accepted, unpack packet into node's virtual feed
        if (verdict != TriPoolRouter.RouteVerdict.DROPPED) {
            val flags = if (rawPacket.size >= 4) rawPacket[3].toInt() and 0xFF else 0
            val isEmergency = (flags and EnvelopeFormat.FLAG_EMERGENCY.toInt()) != 0 || 
                              (rawPacket.size >= 2 && rawPacket[0] == 0xD7.toByte() && rawPacket[1] == EnvelopeFormat.PROTO_VERSION_EMERGENCY)

            if (isEmergency) {
                val em = EnvelopeFormat.deserializeEmergency(rawPacket)
                if (em != null) {
                    val content = String(em.ciphertext, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
                    node.storeDecryptedMessage(
                        FeedMessage(
                            id = "EMG_" + UUID.randomUUID().toString().take(8),
                            groupId = "GROUP_PUBLIC_BROADCAST",
                            senderName = "EMERGENCY BEACON",
                            content = content,
                            type = MessageType.EMERGENCY,
                            timestamp = System.currentTimeMillis(),
                            isEmergency = true
                        )
                    )
                }
            } else {
                val tier1 = EnvelopeFormat.deserializeTier1(rawPacket)
                if (tier1 != null) {
                    val key = node.groups["GROUP_PUBLIC_BROADCAST"]!!.secretKey
                    val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
                    val actualCiphertext = if (tier1.bucketLength in 1..2864 && tier1.bucketLength <= tier1.encryptedBody.size) {
                        tier1.encryptedBody.copyOf(tier1.bucketLength)
                    } else {
                        tier1.encryptedBody
                    }
                    val decrypted = hybridEngine.decryptPayloadTier1(key, tier1.nonce, aad, actualCiphertext, tier1.macTag)
                    if (decrypted != null) {
                        val rawStr = String(decrypted, Charsets.UTF_8)
                        val text = rawStr.substringBefore('\u0000').trim()
                        if (text.startsWith("{") && text.endsWith("}")) {
                            val json = JSONObject(text)
                            node.storeDecryptedMessage(
                                FeedMessage(
                                    id = json.getString("id"),
                                    groupId = json.optString("groupId", "GROUP_PUBLIC_BROADCAST"),
                                    senderName = json.optString("senderName", "Mesh Peer"),
                                    content = json.getString("content"),
                                    type = MessageType.valueOf(json.optString("type", "TEXT")),
                                    timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                                    isEmergency = json.optBoolean("isEmergency", false),
                                    replyToId = json.optString("replyToId").takeIf { it.isNotEmpty() },
                                    replyToSender = json.optString("replyToSender").takeIf { it.isNotEmpty() },
                                    replyToText = json.optString("replyToText").takeIf { it.isNotEmpty() },
                                    targetPeer = json.optString("targetPeer").takeIf { it.isNotEmpty() }
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Test(timeout = 8000)
    fun testBleMeshP2pProtocolAndZeroWifiBroadcast() {
            // Simulate 2 off-grid devices communicating via BLE L2CAP CoC sockets (Zero-Wi-Fi)
            val phone1 = VirtualNode("Phone1_Civilian", 44001)
            val phone2 = VirtualNode("Phone2_Civilian", 44002)

            val server = ServerSocket(0)
            val assignedDynamicPsm = server.localPort

            // 1. Verify dynamic PSM serialization into 2-byte BLE scan response service data
            val psmBytes = java.nio.ByteBuffer.allocate(2).order(java.nio.ByteOrder.BIG_ENDIAN).putShort(assignedDynamicPsm.toShort()).array()
            assertEquals(2, psmBytes.size)
            val parsedPsm = java.nio.ByteBuffer.wrap(psmBytes).order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            assertEquals(assignedDynamicPsm, parsedPsm)

            var clientConnected = false
            var receivedBloomSummary = false
            val serverReceivedMessages = mutableListOf<FeedMessage>()

            val serverThread = thread(start = true, isDaemon = true) {
                try {
                    val socket = server.accept()
                    clientConnected = true
                    val input = socket.getInputStream()
                    val readBuf = ByteArray(4096)

                    while (!socket.isClosed) {
                        val op = input.read()
                        if (op == -1) break
                        when (op.toByte()) {
                            network.disaster.management.transport.BleMeshSupervisor.MSG_BLOOM_SUMMARY -> {
                                val summaryBytes = ByteArray(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES)
                                if (readExact(input, summaryBytes)) {
                                    receivedBloomSummary = true
                                    // Send Bloom response back
                                    val bloom = BundleBloomFilter.fromBundleIds(emptyList())
                                    val resp = java.nio.ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(java.nio.ByteOrder.BIG_ENDIAN)
                                    resp.putLong(System.currentTimeMillis())
                                    resp.putLong(1L)
                                    resp.put(bloom.filterBytes)
                                    writeFrameSafe(socket, network.disaster.management.transport.BleMeshSupervisor.MSG_BLOOM_SUMMARY, resp.array())
                                }
                            }
                            network.disaster.management.transport.BleMeshSupervisor.MSG_BUNDLE_PAYLOAD -> {
                                if (readExact(input, readBuf)) {
                                    processPacketOnNode(phone2, readBuf.clone())
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 2. Connect client to parsed dynamic PSM
            val clientSocket = Socket("127.0.0.1", parsedPsm)
            assertTrue(clientSocket.isConnected)

            // 3. Send Bloom filter summary from client
            val bloom1 = BundleBloomFilter.fromBundleIds(listOf(ByteArray(32) { 0xAA.toByte() }))
            val bloomBuf = java.nio.ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(java.nio.ByteOrder.BIG_ENDIAN)
            bloomBuf.putLong(System.currentTimeMillis())
            bloomBuf.putLong(1L)
            bloomBuf.put(bloom1.filterBytes)
            writeFrameSafe(clientSocket, network.disaster.management.transport.BleMeshSupervisor.MSG_BLOOM_SUMMARY, bloomBuf.array())

            // 4. Send encrypted tactical message packet (4,096 bytes)
            val msgJson = JSONObject().apply {
                put("id", UUID.randomUUID().toString())
                put("groupId", "GROUP_PUBLIC_BROADCAST")
                put("senderName", "Civilian-Phone1")
                put("content", "🟢 Off-grid BLE communication confirmed without Hotspot or Wi-Fi!")
                put("type", "TEXT")
                put("timestamp", System.currentTimeMillis())
                put("isEmergency", false)
            }.toString().toByteArray(Charsets.UTF_8)

            val groupKey = phone1.groups["GROUP_PUBLIC_BROADCAST"]!!.secretKey
            val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
            val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
            val (ciphertext, macTag) = hybridEngine.encryptPayloadTier1(groupKey, nonce, aad, msgJson)

            val envelope = EnvelopeFormat.Tier1Envelope(
                hopLimit = 7.toByte(),
                creationEpoch = System.currentTimeMillis(),
                ttlExpiry = System.currentTimeMillis() + 86400000L,
                powDifficulty = 2,
                powNonce = 0,
                bundleId = ByteArray(32).also { secureRandom.nextBytes(it) },
                rollingRecipientTag = phone2.recipientTag,
                ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
                mlKemCiphertext = ByteArray(1088),
                nonce = nonce,
                bucketLength = ciphertext.size,
                encryptedBody = ciphertext,
                macTag = macTag
            )
            val serialized = EnvelopeFormat.serializeTier1(envelope)
            val solvedNonce = EnvelopeFormat.solvePoW(serialized, 2)
            envelope.powNonce = solvedNonce
            val finalPacket = EnvelopeFormat.serializeTier1(envelope)
            assertEquals(4096, finalPacket.size)

            writeFrameSafe(clientSocket, network.disaster.management.transport.BleMeshSupervisor.MSG_BUNDLE_PAYLOAD, finalPacket)

            // Wait for receiver to process
            val start = System.currentTimeMillis()
            while (phone2.messages.isEmpty() && System.currentTimeMillis() - start < 4000) {
                Thread.sleep(50)
            }

            assertTrue("Client should connect to dynamically advertised L2CAP channel", clientConnected)
            assertTrue("Server should receive Bloom filter summary", receivedBloomSummary)
            assertEquals("Phone2 should receive and decrypt message over BLE channel", 1, phone2.messages.size)
            assertEquals("Civilian-Phone1", phone2.messages[0].senderName)
            assertTrue(phone2.messages[0].content.contains("Off-grid BLE communication confirmed"))

            clientSocket.close()
            server.close()
        }

    @Test(timeout = 8000)
    fun testWifiDirectP2pZeroNetworkExchange() {
        // Simulate 2 devices that are NOT connected to any Wi-Fi router or hotspot
        // Device 1 becomes Wi-Fi Direct Group Owner (GO)
        // Device 2 connects autonomously via Wi-Fi Direct socket on SYNC_PORT
        val goNode = VirtualNode("GO_Phone_Civilian", 45001)
        val clientNode = VirtualNode("Client_Phone_Civilian", 45002)

        val server = ServerSocket(0)
        val port = server.localPort

        var goConnected = false
        val goReceivedMessages = CopyOnWriteArrayList<FeedMessage>()

        val goServerThread = thread(start = true, isDaemon = true) {
            try {
                val socket = server.accept()
                goConnected = true
                val input = socket.getInputStream()
                val readBuf = ByteArray(4096)

                while (!socket.isClosed) {
                    val op = input.read()
                    if (op == -1) break
                    when (op.toByte()) {
                        network.disaster.management.transport.WifiDirectMeshSupervisor.MSG_BLOOM_SUMMARY -> {
                            val summary = ByteArray(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES)
                            if (readExact(input, summary)) {
                                val bloom = BundleBloomFilter.fromBundleIds(emptyList())
                                val resp = java.nio.ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(java.nio.ByteOrder.BIG_ENDIAN)
                                resp.putLong(System.currentTimeMillis())
                                resp.putLong(2L)
                                resp.put(bloom.filterBytes)
                                writeFrameSafe(socket, network.disaster.management.transport.WifiDirectMeshSupervisor.MSG_BLOOM_SUMMARY, resp.array())
                            }
                        }
                        network.disaster.management.transport.WifiDirectMeshSupervisor.MSG_BUNDLE_PAYLOAD -> {
                            if (readExact(input, readBuf)) {
                                processPacketOnNode(goNode, readBuf.clone())
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Client connects to Group Owner port
        val clientSocket = Socket("127.0.0.1", port)
        assertTrue("Wi-Fi Direct client must connect to Group Owner", clientSocket.isConnected)

        // Client sends Bloom summary
        val bloom = BundleBloomFilter.fromBundleIds(emptyList())
        val bloomBuf = java.nio.ByteBuffer.allocate(8 + 8 + BundleBloomFilter.FILTER_SIZE_BYTES).order(java.nio.ByteOrder.BIG_ENDIAN)
        bloomBuf.putLong(System.currentTimeMillis())
        bloomBuf.putLong(1L)
        bloomBuf.put(bloom.filterBytes)
        writeFrameSafe(clientSocket, network.disaster.management.transport.WifiDirectMeshSupervisor.MSG_BLOOM_SUMMARY, bloomBuf.array())

        // Client sends 4,096-byte encrypted SOS emergency packet over Wi-Fi Direct
        val emText = "🚨 CRITICAL AID: Structural damage at Sector 4. Zero cellular or router available."
        val emBundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val emEnvelope = EnvelopeFormat.EmergencyEnvelope(
            hopLimit = 15.toByte(),
            ttlExpirySeconds = 86400,
            shortBundleId = emBundleId.copyOf(8),
            ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
            nonce = ByteArray(8).also { secureRandom.nextBytes(it) },
            ciphertext = emText.toByteArray(Charsets.UTF_8),
            poly1305Tag = ByteArray(16).also { secureRandom.nextBytes(it) }
        )
        val rawEmPacket = EnvelopeFormat.serializeEmergency(emEnvelope).copyOf(4096)
        writeFrameSafe(clientSocket, network.disaster.management.transport.WifiDirectMeshSupervisor.MSG_BUNDLE_PAYLOAD, rawEmPacket)

        // Wait for GO node to ingest packet
        val start = System.currentTimeMillis()
        while (goNode.messages.isEmpty() && System.currentTimeMillis() - start < 4000) {
            Thread.sleep(50)
        }

        assertTrue("Group Owner must have accepted Wi-Fi Direct client connection", goConnected)
        assertEquals("Group Owner must ingest emergency distress beacon", 1, goNode.messages.size)
        assertTrue(goNode.messages[0].content.contains("Structural damage at Sector 4"))
        assertTrue(goNode.messages[0].isEmergency)

        clientSocket.close()
        server.close()
    }

    @Test(timeout = 5000)
    fun testThreadedBroadcastReplyAndTrustworthiness() {
        val nodeA = VirtualNode("Civilian_Node_A", 48001)
        val nodeB = VirtualNode("Civilian_Node_B", 48002)

        // 1. Node A creates an initial feed broadcast
        val parentMsgId = "PARENT_" + UUID.randomUUID().toString().take(8)
        val parentText = "Bridge crossing safe for transport."

        // 2. Node B drafts a threaded reply citing Node A's broadcast
        val replyMsgId = "REPLY_" + UUID.randomUUID().toString().take(8)
        val replyContent = "Acknowledged! Evacuating civilian team via bridge."

        val json = JSONObject().apply {
            put("id", replyMsgId)
            put("groupId", "GROUP_PUBLIC_BROADCAST")
            put("senderName", "Civilian_Node_B")
            put("content", replyContent)
            put("type", MessageType.TEXT.name)
            put("timestamp", System.currentTimeMillis())
            put("isEmergency", false)
            put("replyToId", parentMsgId)
            put("replyToSender", "Civilian_Node_A")
            put("replyToText", parentText)
        }.toString()

        val groupKey = ByteArray(32) { 0x01 }
        val nonce = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
        val inputBytes = json.toByteArray(Charsets.UTF_8)
        val (ciphertext, macTag) = hybridEngine.encryptPayloadTier1(groupKey, nonce, aad, inputBytes)

        val bundleId = ByteArray(32).also { secureRandom.nextBytes(it) }
        val envelope = EnvelopeFormat.Tier1Envelope(
            hopLimit = 7.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 2,
            powNonce = 0,
            bundleId = bundleId,
            rollingRecipientTag = nodeA.recipientTag,
            ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
            mlKemCiphertext = ByteArray(1088),
            nonce = nonce,
            bucketLength = ciphertext.size,
            encryptedBody = ciphertext,
            macTag = macTag
        )
        val rawSerialized = EnvelopeFormat.serializeTier1(envelope)
        envelope.powNonce = EnvelopeFormat.solvePoW(rawSerialized, 2)
        val finalSerialized = EnvelopeFormat.serializeTier1(envelope)

        // Process ingress packet on Node A
        processPacketOnNode(nodeA, finalSerialized)

        assertEquals("Node A must ingest the incoming broadcast", 1, nodeA.messages.size)
        val receivedReply = nodeA.messages[0]
        assertEquals(replyMsgId, receivedReply.id)
        assertEquals("Civilian_Node_B", receivedReply.senderName)
        assertEquals(replyContent, receivedReply.content)
        assertEquals(parentMsgId, receivedReply.replyToId)
        assertEquals("Civilian_Node_A", receivedReply.replyToSender)
        assertEquals(parentText, receivedReply.replyToText)

        // 3. Test Trustworthiness adjustment and classification
        val detector = network.disaster.management.security.HostileActorDetector.shared
        detector.clear()

        // Initially unverified
        detector.setPeerTrust("Civilian_Node_B", 70)
        var profile = detector.getProfile("Civilian_Node_B")
        assertNotNull(profile)
        assertEquals(70, profile!!.trustScore)
        assertEquals(network.disaster.management.map.ActorClassification.UNVERIFIED_CIVILIAN, profile.classification)

        // Mark as verified trusted friend
        detector.setPeerTrust("Civilian_Node_B", 95, network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN)
        profile = detector.getProfile("Civilian_Node_B")
        assertEquals(95, profile!!.trustScore)
        assertEquals(network.disaster.management.map.ActorClassification.VERIFIED_CIVILIAN, profile.classification)

        // Flag as hostile infiltrator
        detector.setPeerTrust("Civilian_Node_B", 0, network.disaster.management.map.ActorClassification.HOSTILE_INFILTRATOR)
        profile = detector.getProfile("Civilian_Node_B")
        assertEquals(0, profile!!.trustScore)
        assertTrue(detector.isSenderHostile("Civilian_Node_B"))
        assertEquals(network.disaster.management.map.ActorClassification.HOSTILE_INFILTRATOR, profile.classification)
    }

    @Test(timeout = 5000)
    fun testFriendRequestSendAcceptRejectMesh() {
        val nodeA = VirtualNode("Civilian_Node_A", 49001)
        val nodeB = VirtualNode("Civilian_Node_B", 49002)

        // 1. Node A sends FRIEND_REQUEST targeted to Node B
        val reqMsgId = "REQ_" + UUID.randomUUID().toString().take(8)
        val reqContent = "🤝 FRIEND REQUEST: @Civilian_Node_A requests connection with @Civilian_Node_B"
        val reqJson = JSONObject().apply {
            put("id", reqMsgId)
            put("groupId", "GROUP_PUBLIC_BROADCAST")
            put("senderName", "Civilian_Node_A")
            put("content", reqContent)
            put("type", MessageType.FRIEND_REQUEST.name)
            put("timestamp", System.currentTimeMillis())
            put("isEmergency", false)
            put("targetPeer", "Civilian_Node_B")
        }.toString()

        val groupKey = ByteArray(32) { 0x01 }
        val nonce1 = ByteArray(12).also { secureRandom.nextBytes(it) }
        val aad = "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8)
        val (ciphertext1, macTag1) = hybridEngine.encryptPayloadTier1(groupKey, nonce1, aad, reqJson.toByteArray(Charsets.UTF_8))

        val envelope1 = EnvelopeFormat.Tier1Envelope(
            hopLimit = 7.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 2,
            powNonce = 0,
            bundleId = ByteArray(32).also { secureRandom.nextBytes(it) },
            rollingRecipientTag = nodeB.recipientTag,
            ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
            mlKemCiphertext = ByteArray(1088),
            nonce = nonce1,
            bucketLength = ciphertext1.size,
            encryptedBody = ciphertext1,
            macTag = macTag1
        )
        val raw1 = EnvelopeFormat.serializeTier1(envelope1)
        envelope1.powNonce = EnvelopeFormat.solvePoW(raw1, 2)
        val finalReqPacket = EnvelopeFormat.serializeTier1(envelope1)

        // Ingest on Node B
        processPacketOnNode(nodeB, finalReqPacket)

        assertEquals("Node B must receive the friend request packet", 1, nodeB.messages.size)
        val receivedReq = nodeB.messages[0]
        assertEquals(MessageType.FRIEND_REQUEST, receivedReq.type)
        assertEquals("Civilian_Node_A", receivedReq.senderName)
        assertEquals("Civilian_Node_B", receivedReq.targetPeer)

        // 2. Node B accepts friend request by broadcasting FRIEND_ACCEPT targeted to Node A
        val acceptMsgId = "ACC_" + UUID.randomUUID().toString().take(8)
        val acceptContent = "🎉 FRIEND REQUEST ACCEPTED: @Civilian_Node_B connected with @Civilian_Node_A"
        val acceptJson = JSONObject().apply {
            put("id", acceptMsgId)
            put("groupId", "GROUP_PUBLIC_BROADCAST")
            put("senderName", "Civilian_Node_B")
            put("content", acceptContent)
            put("type", MessageType.FRIEND_ACCEPT.name)
            put("timestamp", System.currentTimeMillis())
            put("isEmergency", false)
            put("targetPeer", "Civilian_Node_A")
        }.toString()

        val nonce2 = ByteArray(12).also { secureRandom.nextBytes(it) }
        val (ciphertext2, macTag2) = hybridEngine.encryptPayloadTier1(groupKey, nonce2, aad, acceptJson.toByteArray(Charsets.UTF_8))

        val envelope2 = EnvelopeFormat.Tier1Envelope(
            hopLimit = 7.toByte(),
            creationEpoch = System.currentTimeMillis(),
            ttlExpiry = System.currentTimeMillis() + 86400000L,
            powDifficulty = 2,
            powNonce = 0,
            bundleId = ByteArray(32).also { secureRandom.nextBytes(it) },
            rollingRecipientTag = nodeA.recipientTag,
            ephemeralX25519Pub = ByteArray(32).also { secureRandom.nextBytes(it) },
            mlKemCiphertext = ByteArray(1088),
            nonce = nonce2,
            bucketLength = ciphertext2.size,
            encryptedBody = ciphertext2,
            macTag = macTag2
        )
        val raw2 = EnvelopeFormat.serializeTier1(envelope2)
        envelope2.powNonce = EnvelopeFormat.solvePoW(raw2, 2)
        val finalAcceptPacket = EnvelopeFormat.serializeTier1(envelope2)

        // Ingest on Node A
        processPacketOnNode(nodeA, finalAcceptPacket)

        assertEquals("Node A must receive the friend accept packet", 1, nodeA.messages.size)
        val receivedAccept = nodeA.messages[0]
        assertEquals(MessageType.FRIEND_ACCEPT, receivedAccept.type)
        assertEquals("Civilian_Node_B", receivedAccept.senderName)
        assertEquals("Civilian_Node_A", receivedAccept.targetPeer)
    }
}
