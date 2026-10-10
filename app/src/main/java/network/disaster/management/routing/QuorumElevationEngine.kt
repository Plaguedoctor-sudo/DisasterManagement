package network.disaster.management.routing

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer

class QuorumElevationEngine(
    private val localSignKey: ByteArray,
    private val localPubkey: ByteArray
) {
    private val QUORUM_MAGIC: Short = 0x5155.toShort()
    private val REQUIRED_WITNESSES = 3

    data class WitnessSlip(val fileHash: ByteArray, val witnessPub: ByteArray, val sig: ByteArray, val expiry: Long)

    fun createLocalEndorsement(
        fileHash: ByteArray,
        expiry: Long = System.currentTimeMillis() + (12 * 3600 * 1000L)
    ): WitnessSlip {
        val payload = ByteBuffer.allocate(40).put(fileHash).putLong(expiry).array()

        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(localSignKey, 0))
        signer.update(payload, 0, payload.size)
        val sig = signer.generateSignature()

        return WitnessSlip(fileHash, localPubkey, sig, expiry)
    }

    fun assembleQuorumCertificate(slips: List<WitnessSlip>, fileHash: ByteArray): ByteArray {
        require(slips.size >= REQUIRED_WITNESSES) { "Quorum certificate requires at least $REQUIRED_WITNESSES witnesses" }
        val expiry = slips.minOf { it.expiry }
        val certLen = 2 + 1 + 1 + 8 + 32 + (slips.size * (32 + 64))
        val buf = ByteBuffer.allocate(certLen)
        buf.putShort(QUORUM_MAGIC)
        buf.put(0x01.toByte()) // Version
        buf.put(slips.size.toByte())
        buf.putLong(expiry)
        buf.put(fileHash)
        for (slip in slips) {
            buf.put(slip.witnessPub)
            buf.put(slip.sig)
        }
        return buf.array()
    }

    fun verifyQuorumCertificate(rawCert: ByteArray, expectedFileHash: ByteArray): Boolean {
        if (rawCert.size < 44) return false
        val buf = ByteBuffer.wrap(rawCert)
        if (buf.short != QUORUM_MAGIC) return false

        buf.get() // version
        val count = buf.get().toInt() and 0xFF
        val expiry = buf.long
        if (count < REQUIRED_WITNESSES || System.currentTimeMillis() > expiry) return false

        val requiredLen = 44 + (count * 96)
        if (rawCert.size < requiredLen) return false

        val hash = ByteArray(32)
        buf.get(hash)
        if (!hash.contentEquals(expectedFileHash)) return false

        val payload = ByteBuffer.allocate(40).put(expectedFileHash).putLong(expiry).array()
        val seen = mutableSetOf<String>()

        for (i in 0 until count) {
            val pub = ByteArray(32)
            val sig = ByteArray(64)
            buf.get(pub)
            buf.get(sig)

            val hex = pub.joinToString("") { "%02x".format(it) }
            if (seen.contains(hex)) return false
            seen.add(hex)

            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
            verifier.update(payload, 0, payload.size)
            if (!verifier.verifySignature(sig)) return false
        }
        return true
    }
}
