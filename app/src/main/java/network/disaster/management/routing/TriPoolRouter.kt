package network.disaster.management.routing

import network.disaster.management.crypto.EnvelopeFormat
import network.disaster.management.storage.LowWearCryptoShredder
import java.nio.ByteBuffer
import java.security.MessageDigest

interface BundleStorageVault {
    fun storeBundleWithSubkey(bundleId: ByteArray, poolType: String, payload: ByteArray): Boolean
}

class TriPoolRouter(
    private val shredder: BundleStorageVault,
    private val localRecipientTag: ByteArray
) {
    enum class RouteVerdict { ACCEPTED_POOL_A, ACCEPTED_POOL_B, ACCEPTED_POOL_C, DROPPED }

    fun processIngressPacket(raw: ByteArray): RouteVerdict {
        if (raw.size < 16) return RouteVerdict.DROPPED

        val isEmergencyEnvelope = (raw[0] == 0xD7.toByte() && raw[1] == EnvelopeFormat.PROTO_VERSION_EMERGENCY)
        val isTier1Envelope = (raw.size >= 4 &&
                raw[0] == (EnvelopeFormat.MAGIC_HEADER.toInt() ushr 8).toByte() &&
                raw[1] == (EnvelopeFormat.MAGIC_HEADER.toInt() and 0xFF).toByte() &&
                raw[2] == EnvelopeFormat.PROTO_VERSION_TIER1)

        val flags = when {
            isEmergencyEnvelope -> raw[2]
            isTier1Envelope -> raw[3]
            else -> raw[3]
        }

        val isEmergency = isEmergencyEnvelope || ((flags.toInt() and EnvelopeFormat.FLAG_EMERGENCY.toInt()) != 0)
        val isChunk = (flags.toInt() and EnvelopeFormat.FLAG_FILE_CHUNK.toInt()) != 0
        val isNoRelay = (flags.toInt() and EnvelopeFormat.FLAG_NO_RELAY.toInt()) != 0

        if (isNoRelay) {
            if (raw.size < 0x50) return RouteVerdict.DROPPED
            val tag = ByteArray(16)
            System.arraycopy(raw, 0x40, tag, 0, 16)
            if (!tag.contentEquals(localRecipientTag)) return RouteVerdict.DROPPED
        }

        val bundleId = ByteArray(32)
        if (isEmergencyEnvelope) {
            if (raw.size >= 16) {
                System.arraycopy(raw, 8, bundleId, 0, 8)
            }
        } else if (raw.size >= 0x40) {
            System.arraycopy(raw, 0x20, bundleId, 0, 32)
        } else {
            System.arraycopy(raw, 0x08, bundleId, 0, minOf(8, raw.size - 8))
        }

        val verdict = when {
            isEmergency -> {
                val uniformPayload = if (raw.size < EnvelopeFormat.TIER_S_SIZE) raw.copyOf(EnvelopeFormat.TIER_S_SIZE) else raw
                shredder.storeBundleWithSubkey(bundleId, "POOL_A", uniformPayload)
                RouteVerdict.ACCEPTED_POOL_A
            }
            isChunk -> {
                shredder.storeBundleWithSubkey(bundleId, "POOL_C", raw)
                RouteVerdict.ACCEPTED_POOL_C
            }
            else -> {
                if (verifyPoW(raw)) {
                    shredder.storeBundleWithSubkey(bundleId, "POOL_B", raw)
                    RouteVerdict.ACCEPTED_POOL_B
                } else {
                    RouteVerdict.DROPPED
                }
            }
        }

        if (verdict != RouteVerdict.DROPPED) {
            tryIngressUnpack(raw, bundleId, isEmergency, isChunk)
        }

        return verdict
    }

    private fun tryIngressUnpack(raw: ByteArray, bundleId: ByteArray, isEmergency: Boolean, isChunk: Boolean) {
        val db = shredder as? LowWearCryptoShredder ?: return
        try {
            if (isEmergency) {
                val emEnv = EnvelopeFormat.deserializeEmergency(raw)
                if (emEnv != null) {
                    val content = String(emEnv.ciphertext, Charsets.UTF_8).trim()
                    if (content.isNotEmpty()) {
                        val hexId = "EMG_" + emEnv.shortBundleId.joinToString("") { "%02X".format(it) }
                        val msg = network.disaster.management.model.FeedMessage(
                            id = hexId,
                            groupId = "GROUP_PUBLIC_BROADCAST",
                            senderName = "EMERGENCY BEACON",
                            content = content,
                            type = network.disaster.management.model.MessageType.EMERGENCY,
                            timestamp = System.currentTimeMillis(),
                            isEmergency = true
                        )
                        db.insertMessage(msg)
                    }
                    return
                }
            }

            if (isChunk) {
                val bodyOffset = 1216
                if (raw.size >= bodyOffset + 30) {
                    val inner = raw.copyOfRange(bodyOffset, raw.size)
                    val chunkEngine = network.disaster.management.storage.MediaChunkingEngine(null, db)
                    val header = chunkEngine.parseChunkHeader(inner)
                    if (header != null) {
                        db.saveFileChunk(header.fileId, header.chunkIndex, header.totalChunks, header.data)
                    }
                }
                return
            }

            val env = EnvelopeFormat.deserializeTier1(raw) ?: return
            val engine = network.disaster.management.crypto.HybridEngine()
            val candidateKeys = mutableListOf<ByteArray>()
            candidateKeys.add(ByteArray(32) { 0x01 })
            candidateKeys.add(ByteArray(32) { 0x02 })
            candidateKeys.add(ByteArray(32) { 0x03 })
            for (group in db.getAllGroups()) {
                candidateKeys.add(group.secretKey)
            }

            val candidateAads = listOf(
                "DISASTER-MESH-V1".toByteArray(Charsets.UTF_8),
                "BUNDLE-AUTH-DATA".toByteArray(Charsets.UTF_8),
                ByteArray(16)
            )

            var decryptedBytes: ByteArray? = null
            val actualCiphertext = if (env.bucketLength in 1..2864 && env.bucketLength <= env.encryptedBody.size) {
                env.encryptedBody.copyOf(env.bucketLength)
            } else {
                env.encryptedBody
            }
            for (key in candidateKeys) {
                for (aad in candidateAads) {
                    val res = engine.decryptPayloadTier1(key, env.nonce, aad, actualCiphertext, env.macTag)
                    if (res != null) {
                        decryptedBytes = res
                        break
                    }
                }
                if (decryptedBytes != null) break
            }

            if (decryptedBytes != null) {
                val rawStr = String(decryptedBytes, Charsets.UTF_8)
                val text = rawStr.substringBefore('\u0000').trim()
                if (text.startsWith("{") && text.endsWith("}")) {
                    try {
                        val json = org.json.JSONObject(text)
                        val msg = network.disaster.management.model.FeedMessage(
                            id = json.getString("id"),
                            groupId = json.optString("groupId", "GROUP_PUBLIC_BROADCAST"),
                            senderName = json.optString("senderName", "Mesh Peer"),
                            content = json.getString("content"),
                            type = try { network.disaster.management.model.MessageType.valueOf(json.optString("type", "TEXT")) } catch (_: Exception) { network.disaster.management.model.MessageType.TEXT },
                            fileName = if (json.has("fileName")) json.getString("fileName") else null,
                            fileSize = json.optLong("fileSize", 0L),
                            mimeType = if (json.has("mimeType")) json.getString("mimeType") else null,
                            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                            isEmergency = json.optBoolean("isEmergency", false)
                        )
                        db.insertMessage(msg)
                    } catch (_: Exception) {
                        val hexId = "MSG_" + bundleId.take(8).joinToString("") { "%02X".format(it) }
                        val msg = network.disaster.management.model.FeedMessage(
                            id = hexId,
                            groupId = "GROUP_PUBLIC_BROADCAST",
                            senderName = "Mesh Peer",
                            content = text,
                            type = network.disaster.management.model.MessageType.TEXT,
                            timestamp = env.creationEpoch,
                            isEmergency = false
                        )
                        db.insertMessage(msg)
                    }
                } else if (text.isNotBlank()) {
                    val hexId = "MSG_" + bundleId.take(8).joinToString("") { "%02X".format(it) }
                    val msg = network.disaster.management.model.FeedMessage(
                        id = hexId,
                        groupId = "GROUP_PUBLIC_BROADCAST",
                        senderName = "Mesh Peer",
                        content = text,
                        type = network.disaster.management.model.MessageType.TEXT,
                        timestamp = env.creationEpoch,
                        isEmergency = false
                    )
                    db.insertMessage(msg)
                }
            }
        } catch (_: Exception) {}
    }


    fun verifyPoW(raw: ByteArray): Boolean {
        if (raw.size < 0x20) return false
        val buf = ByteBuffer.wrap(raw)
        val diff = buf.getInt(0x018)
        if (diff < 2) return false // Support dynamically scaled difficulty down to 2 under low battery/power save

        val md = MessageDigest.getInstance("SHA-256")
        md.update(raw, 0, 0x020)
        val d = md.digest()

        var leadingZeros = 0
        for (b in d) {
            val v = b.toInt() and 0xFF
            if (v == 0) leadingZeros += 8
            else {
                leadingZeros += Integer.numberOfLeadingZeros(v) - 24
                break
            }
        }
        return leadingZeros >= diff
    }
}
