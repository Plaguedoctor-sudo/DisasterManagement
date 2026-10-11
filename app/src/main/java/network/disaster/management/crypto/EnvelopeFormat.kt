package network.disaster.management.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object EnvelopeFormat {
    const val MAGIC_HEADER: Short = 0xD74E.toShort()
    const val PROTO_VERSION_TIER1: Byte = 0x04
    const val PROTO_VERSION_EMERGENCY: Byte = 0x05

    const val FLAG_ENCRYPTED: Byte = 0x01
    const val FLAG_EMERGENCY: Byte = 0x02
    const val FLAG_DELIVERY_ACK: Byte = 0x04
    const val FLAG_FILE_MANIFEST: Byte = 0x08
    const val FLAG_FILE_CHUNK: Byte = 0x10
    const val FLAG_NO_RELAY: Byte = 0x20

    const val TIER_S_SIZE = 4096

    data class Tier1Envelope(
        val hopLimit: Byte,
        val creationEpoch: Long,
        val ttlExpiry: Long,
        var powDifficulty: Int,
        var powNonce: Int,
        val bundleId: ByteArray,             // 32 bytes
        val rollingRecipientTag: ByteArray,  // 16 bytes
        val ephemeralX25519Pub: ByteArray,   // 32 bytes
        val mlKemCiphertext: ByteArray,      // 1088 bytes
        val nonce: ByteArray,                // 12 bytes
        val bucketLength: Int,               // 4 bytes
        val encryptedBody: ByteArray,        // 2864 bytes
        val macTag: ByteArray,               // 16 bytes
        val flags: Byte = FLAG_ENCRYPTED     // 1 byte flags
    )

    fun serializeTier1(env: Tier1Envelope): ByteArray {
        val buffer = ByteBuffer.allocate(TIER_S_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(MAGIC_HEADER)
        buffer.put(PROTO_VERSION_TIER1)
        buffer.put((env.flags.toInt() or FLAG_ENCRYPTED.toInt()).toByte())
        buffer.put(env.hopLimit)
        buffer.put(ByteArray(3))
        buffer.putLong(env.creationEpoch)
        buffer.putLong(env.ttlExpiry)
        buffer.putInt(env.powDifficulty)
        buffer.putInt(env.powNonce)
        buffer.put(env.bundleId)
        buffer.put(env.rollingRecipientTag)
        buffer.put(env.ephemeralX25519Pub)
        buffer.put(env.mlKemCiphertext)
        buffer.put(env.nonce)
        buffer.putInt(env.bucketLength)
        val bodyBytes = if (env.encryptedBody.size == 2864) env.encryptedBody else env.encryptedBody.copyOf(2864)
        buffer.put(bodyBytes)
        buffer.put(env.macTag)
        return buffer.array()
    }

    fun deserializeTier1(raw: ByteArray): Tier1Envelope? {
        if (raw.size != TIER_S_SIZE) return null
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.short
            if (magic != MAGIC_HEADER) return null
            val proto = buffer.get()
            if (proto != PROTO_VERSION_TIER1) return null
            val flags = buffer.get()
            val hopLimit = buffer.get()
            val pad = ByteArray(3)
            buffer.get(pad)
            val creationEpoch = buffer.long
            val ttlExpiry = buffer.long
            val powDifficulty = buffer.int
            val powNonce = buffer.int
            val bundleId = ByteArray(32).also { buffer.get(it) }
            val recipientTag = ByteArray(16).also { buffer.get(it) }
            val x25519 = ByteArray(32).also { buffer.get(it) }
            val mlKem = ByteArray(1088).also { buffer.get(it) }
            val nonce = ByteArray(12).also { buffer.get(it) }
            val bucketLength = buffer.int
            val body = ByteArray(2864).also { buffer.get(it) }
            val mac = ByteArray(16).also { buffer.get(it) }
            Tier1Envelope(
                hopLimit, creationEpoch, ttlExpiry, powDifficulty, powNonce,
                bundleId, recipientTag, x25519, mlKem, nonce, bucketLength, body, mac, flags
            )

        } catch (_: Exception) {
            null
        }
    }

    data class EmergencyEnvelope(
        val hopLimit: Byte,
        val ttlExpirySeconds: Int,
        val shortBundleId: ByteArray,        // 8 bytes
        val ephemeralX25519Pub: ByteArray,   // 32 bytes
        val nonce: ByteArray,                // 8 bytes
        val ciphertext: ByteArray,
        val poly1305Tag: ByteArray           // 16 bytes
    )

    fun serializeEmergency(env: EmergencyEnvelope): ByteArray {
        require(env.ciphertext.size <= 65535) { "Emergency ciphertext exceeds maximum size (65535 bytes)" }
        // Header: 1B magic + 1B proto + 1B flags + 1B hopLimit + 4B ttl + 8B bundleId + 32B x25519 + 8B nonce + 2B cipherLen + cipher + 16B tag
        val total = 4 + 4 + 8 + 32 + 8 + 2 + env.ciphertext.size + 16
        val buffer = ByteBuffer.allocate(total).order(ByteOrder.BIG_ENDIAN)
        buffer.put(0xD7.toByte())
        buffer.put(PROTO_VERSION_EMERGENCY)
        buffer.put((FLAG_ENCRYPTED.toInt() or FLAG_EMERGENCY.toInt()).toByte())
        buffer.put(env.hopLimit)
        buffer.putInt(env.ttlExpirySeconds)
        buffer.put(env.shortBundleId)
        buffer.put(env.ephemeralX25519Pub)
        buffer.put(env.nonce)
        buffer.putShort(env.ciphertext.size.toShort())
        buffer.put(env.ciphertext)
        buffer.put(env.poly1305Tag)
        return buffer.array()
    }

    fun deserializeEmergency(raw: ByteArray): EmergencyEnvelope? {
        if (raw.size < 71) return null
        return try {
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
            val magic = buffer.get()
            if (magic != 0xD7.toByte()) return null
            val proto = buffer.get()
            if (proto != PROTO_VERSION_EMERGENCY) return null
            val flags = buffer.get()
            val hopLimit = buffer.get()
            val ttl = buffer.int
            val bundleId = ByteArray(8).also { buffer.get(it) }
            val x25519 = ByteArray(32).also { buffer.get(it) }
            val nonce = ByteArray(8).also { buffer.get(it) }
            val cipherLen = buffer.short.toInt() and 0xFFFF
            if (buffer.remaining() < cipherLen + 16) return null
            val ciphertext = ByteArray(cipherLen).also { buffer.get(it) }
            val tag = ByteArray(16).also { buffer.get(it) }
            EmergencyEnvelope(hopLimit, ttl, bundleId, x25519, nonce, ciphertext, tag)
        } catch (_: Exception) {
            null
        }
    }

    fun solvePoW(rawEnvelope: ByteArray, targetDifficulty: Int): Int {
        if (rawEnvelope.size < 32) return 0
        val clone = rawEnvelope.clone()
        val buf = ByteBuffer.wrap(clone).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x018, targetDifficulty)
        val md = MessageDigest.getInstance("SHA-256")
        for (nonce in 0..Int.MAX_VALUE) {
            buf.putInt(0x01C, nonce)
            md.reset()
            md.update(clone, 0, 32)
            val digest = md.digest()
            var leadingZeros = 0
            for (b in digest) {
                val v = b.toInt() and 0xFF
                if (v == 0) leadingZeros += 8
                else {
                    leadingZeros += Integer.numberOfLeadingZeros(v) - 24
                    break
                }
            }
            if (leadingZeros >= targetDifficulty) {
                return nonce
            }
        }
        return 0
    }
}
