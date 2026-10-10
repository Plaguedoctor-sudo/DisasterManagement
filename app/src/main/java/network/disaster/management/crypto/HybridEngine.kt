package network.disaster.management.crypto

import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.*
import org.bouncycastle.pqc.crypto.crystals.kyber.*
import java.nio.ByteBuffer
import java.security.SecureRandom

class HybridEngine {
    private val secureRandom = SecureRandom()

    // --- Classical X25519 ---
    fun generateX25519KeyPair(): AsymmetricCipherKeyPair {
        val gen = X25519KeyPairGenerator()
        gen.init(X25519KeyGenerationParameters(secureRandom))
        return gen.generateKeyPair()
    }

    fun calculateX25519Agreement(privKey: AsymmetricKeyParameter, pubKey: AsymmetricKeyParameter): ByteArray {
        val agree = X25519Agreement()
        agree.init(privKey)
        val secret = ByteArray(32)
        agree.calculateAgreement(pubKey, secret, 0)
        return secret
    }

    // --- Post-Quantum ML-KEM-768 (Kyber768) ---
    fun generateKyber768KeyPair(): AsymmetricCipherKeyPair {
        val gen = KyberKeyPairGenerator()
        gen.init(KyberKeyGenerationParameters(secureRandom, KyberParameters.kyber768))
        return gen.generateKeyPair()
    }

    fun encapsulateKyber768(recipientPub: AsymmetricKeyParameter): Pair<ByteArray, ByteArray> {
        val kemGen = KyberKEMGenerator(secureRandom)
        val encResult = kemGen.generateEncapsulated(recipientPub)
        return Pair(encResult.secret, encResult.encapsulation)
    }

    fun decapsulateKyber768(localPriv: AsymmetricKeyParameter, ciphertext: ByteArray): ByteArray {
        val kemExt = KyberKEMExtractor(localPriv as KyberPrivateKeyParameters)
        return kemExt.extractSecret(ciphertext)
    }

    // --- Hybrid HKDF Key Derivation ---
    fun deriveHybridSharedKey(
        classicalSecret: ByteArray,
        quantumSecret: ByteArray,
        salt: ByteArray
    ): ByteArray {
        val ikm = ByteArray(classicalSecret.size + quantumSecret.size)
        System.arraycopy(classicalSecret, 0, ikm, 0, classicalSecret.size)
        System.arraycopy(quantumSecret, 0, ikm, classicalSecret.size, quantumSecret.size)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(ikm, salt, "DTN-PQ-HYBRID-V3".toByteArray(Charsets.UTF_8)))
        val derived = ByteArray(32)
        hkdf.generateBytes(derived, 0, 32)
        return derived
    }

    // --- 4,096-Byte Bucket Authenticated ChaCha20-Poly1305 ---
    fun encryptPayloadTier1(
        derivedKey: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        plaintext: ByteArray
    ): Pair<ByteArray, ByteArray> {
        val capacity = 2864
        val prefix = 4
        require(plaintext.size <= capacity - prefix) { "Payload exceeds bucket profile capacity" }

        val innerBody = ByteArray(capacity)
        val buf = ByteBuffer.wrap(innerBody)
        buf.putInt(plaintext.size)
        buf.put(plaintext)

        val chaffLen = capacity - prefix - plaintext.size
        if (chaffLen > 0) {
            val chaff = ByteArray(chaffLen)
            secureRandom.nextBytes(chaff)
            buf.put(chaff)
        }

        val cipher = ChaCha20Poly1305()
        cipher.init(true, ParametersWithIV(KeyParameter(derivedKey), nonce))
        cipher.processAADBytes(associatedData, 0, associatedData.size)

        val out = ByteArray(cipher.getOutputSize(innerBody.size))
        val len = cipher.processBytes(innerBody, 0, innerBody.size, out, 0)
        cipher.doFinal(out, len)

        val outCiphertext = out.copyOfRange(0, capacity)
        val macTag = out.copyOfRange(capacity, capacity + 16)

        return Pair(outCiphertext, macTag)
    }

    fun decryptPayloadTier1(
        derivedKey: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        ciphertext: ByteArray,
        macTag: ByteArray
    ): ByteArray? {
        return try {
            val cipher = ChaCha20Poly1305()
            cipher.init(false, ParametersWithIV(KeyParameter(derivedKey), nonce))
            cipher.processAADBytes(associatedData, 0, associatedData.size)

            val combined = ByteArray(ciphertext.size + macTag.size)
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
            System.arraycopy(macTag, 0, combined, ciphertext.size, macTag.size)

            val decryptedBody = ByteArray(cipher.getOutputSize(combined.size))
            val len = cipher.processBytes(combined, 0, combined.size, decryptedBody, 0)
            val finalLen = len + cipher.doFinal(decryptedBody, len)

            val buf = ByteBuffer.wrap(decryptedBody, 0, finalLen)
            val realLength = buf.int
            if (realLength < 0 || realLength > finalLen - 4) return null

            val realPlaintext = ByteArray(realLength)
            buf.get(realPlaintext)
            realPlaintext
        } catch (_: Exception) {
            null
        }
    }
}
